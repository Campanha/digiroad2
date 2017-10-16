package fi.liikennevirasto.digiroad2.masstransitstop

import java.util.{Date, NoSuchElementException}

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.masstransitstop.oracle.{AssetPropertyConfiguration, MassTransitStopDao, Queries, Sequences}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.GeometryTransform

import scala.util.Try

class TierekisteriBusStopStrategy(typeId : Int, massTransitStopDao: MassTransitStopDao, roadLinkService: RoadLinkService, tierekisteriClient: TierekisteriMassTransitStopClient, geometryTransform: GeometryTransform) extends BusStopStrategy(typeId, massTransitStopDao, roadLinkService)
{
  val toLiviId = "OTHJ%d"
  val MaxMovementDistanceMeters = 50

  lazy val massTransitStopEnumeratedPropertyValues = {
      val properties = Queries.getEnumeratedPropertyValues(typeId)
      properties.map(epv => epv.publicId -> epv.values).toMap
  }

  override def is(newProperties: Set[SimpleProperty], roadLink: Option[RoadLink], existingAssetOption: Option[PersistedMassTransitStop]): Boolean = {
    //TODO Check if this is really needed
    val properties = existingAssetOption match {
      case Some(existingAsset) =>
        (existingAsset.propertyData.
          filterNot(property => newProperties.exists(_.publicId == property.publicId)).
          map(property => SimpleProperty(property.publicId, property.values)) ++ newProperties).
          filterNot(property => AssetPropertyConfiguration.commonAssetProperties.exists(_._1 == property.publicId))
      case _ => newProperties.toSeq
    }

    isStoredInTierekisteri(properties, roadLink.map(_.administrativeClass))
  }

  override def was(existingAsset: PersistedMassTransitStop): Boolean = {
    val administrationClass = MassTransitStopOperations.getAdministrationClass(existingAsset.propertyData)
    isStoredInTierekisteri(existingAsset.propertyData, administrationClass)
  }

  override def undo(existingAsset: PersistedMassTransitStop, newProperties: Set[SimpleProperty], username: String): Unit = {
    //Remove the Livi ID
    massTransitStopDao.updateTextPropertyValue(existingAsset.id, MassTransitStopOperations.LiViIdentifierPublicId, "")

    getLiviIdValue(existingAsset.propertyData).map {
      liviId =>
        if(MassTransitStopOperations.isVirtualBusStop(newProperties))
          deleteTierekisteriBusStop(liviId)
        else
          expireTierekisteriBusStop(existingAsset, liviId, username)
    }
  }

  override def enrichBusStop(persistedStop: PersistedMassTransitStop): (PersistedMassTransitStop, Boolean) = {
    val enrichPersistedStop = { super.enrichBusStop(persistedStop)._1 }
    val properties = enrichPersistedStop.propertyData
    val liViProp = properties.find(_.publicId == MassTransitStopOperations.LiViIdentifierPublicId)
    val liViId = liViProp.flatMap(_.values.headOption).map(_.propertyValue)
    val tierekisteriStop = liViId.flatMap(tierekisteriClient.fetchMassTransitStop)
    tierekisteriStop.isEmpty match {
      case true => (enrichPersistedStop, true)
      case false => (enrichWithTierekisteriInfo(enrichPersistedStop, tierekisteriStop.get), false)
    }
  }

  override def create(asset: NewMassTransitStop, username: String, point: Point, geometry: Seq[Point], municipality: Int, administrativeClass: Option[AdministrativeClass], linkSource: LinkGeomSource, roadLink: RoadLink): PersistedMassTransitStop = {
    val assetId = Sequences.nextPrimaryKeySeqValue
    val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
    val nationalId = massTransitStopDao.getNationalBusStopId
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(point, geometry)
    val newAssetPoint = GeometryUtils.calculatePointFromLinearReference(geometry, mValue).getOrElse(Point(asset.lon, asset.lat))
    val floating = !PointAssetOperations.coordinatesWithinThreshold(Some(point), GeometryUtils.calculatePointFromLinearReference(geometry, mValue))
    massTransitStopDao.insertLrmPosition(lrmPositionId, mValue, asset.linkId, linkSource)
    massTransitStopDao.insertAsset(assetId, nationalId, newAssetPoint.x, newAssetPoint.y, asset.bearing, username, municipality, floating)
    massTransitStopDao.insertAssetLink(assetId, lrmPositionId)

    val properties = setPropertiesDefaultValues(asset.properties, roadLink)

    val defaultValues = massTransitStopDao.propertyDefaultValues(typeId).filterNot(defaultValue => properties.exists(_.publicId == defaultValue.publicId))
    if (MassTransitStopOperations.mixedStoptypes(properties.toSet))
      throw new IllegalArgumentException

    massTransitStopDao.updateAssetProperties(assetId, properties ++ defaultValues.toSet)
    updateAdministrativeClassValue(assetId, administrativeClass.getOrElse(throw new IllegalArgumentException("AdministrativeClass argument is mandatory")))
    val newAdminClassProperty = SimpleProperty(MassTransitStopOperations.MassTransitStopAdminClassPublicId, Seq(PropertyValue(administrativeClass.getOrElse(Unknown).value.toString)))
    //TODO This variable is not been in use check what is missing
    val propsWithAdminClass = properties.filterNot(_.publicId == MassTransitStopOperations.MassTransitStopAdminClassPublicId) ++ Seq(newAdminClassProperty)

    val liviId = toLiviId.format(nationalId)
    massTransitStopDao.updateTextPropertyValue(assetId, MassTransitStopOperations.LiViIdentifierPublicId, liviId)

    val persistedAsset = fetchAsset(assetId)

    createTierekisteriBusStop(persistedAsset, roadLink, liviId)

    persistedAsset
  }

  //TODO this can be improved for sure
  override def update(asset: PersistedMassTransitStop, optionalPosition: Option[Position], props: Set[SimpleProperty], username: String, municipalityValidation: (Int) => Unit, roadLink: RoadLink): PersistedMassTransitStop = {
    val properties = setPropertiesDefaultValues(props.toSeq, roadLink).toSet

    if (MassTransitStopOperations.mixedStoptypes(properties))
      throw new IllegalArgumentException

    municipalityValidation(asset.municipalityCode)

    val (municipalityCode, geometry) = (roadLink.municipalityCode, roadLink.geometry)

    // Enrich properties with old administrator, if administrator value is empty in CSV import
    val verifiedProperties = MassTransitStopOperations.getVerifiedProperties(properties, asset.propertyData)

    //Remove from common assets the side code property
    val commonAssetProperties = AssetPropertyConfiguration.commonAssetProperties.
      filterNot(_._1 == AssetPropertyConfiguration.ValidityDirectionId)

    val mergedProperties = (asset.propertyData.
      filterNot(property => properties.exists(_.publicId == property.publicId)).
      map(property => SimpleProperty(property.publicId, property.values)) ++ properties).
      filterNot(property => commonAssetProperties.exists(_._1 == property.publicId))

    //If it was already in Tierekisteri
    if (was(asset)) {
      val liviId = getLiviIdValue(asset.propertyData).orElse(getLiviIdValue(properties.toSeq)).getOrElse(throw new NoSuchElementException)
      if (calculateMovedDistance(asset, optionalPosition) > MaxMovementDistanceMeters) {
        val position = optionalPosition.get
        val newInventoryDateValue =
          asset.propertyData.filter(_.publicId == MassTransitStopOperations.InventoryDateId).map(prop =>
            Property(prop.id, prop.publicId, prop.propertyType, prop.required, Seq())
          )
        val newPropertyData = asset.propertyData.filterNot(_.publicId == MassTransitStopOperations.InventoryDateId) ++ newInventoryDateValue
        val newAsset = asset.copy(propertyData = newPropertyData)

        //Expire the old asset
        expireMassTransitStop(username, liviId, newAsset)

        //Remove the InventoryDate Property to used the actual instead the old value when create a new asset
        val mergedPropertiesWithOutInventoryDate = mergedProperties.filterNot(_.publicId == MassTransitStopOperations.InventoryDateId)

        //Create a new asset
        create(NewMassTransitStop(position.lon, position.lat, roadLink.linkId, position.bearing.getOrElse(asset.bearing.get),
          mergedPropertiesWithOutInventoryDate), username, Point(position.lon, position.lat), geometry, municipalityCode, Some(roadLink.administrativeClass), roadLink.linkSource, roadLink)

      }else{
        optionalPosition.map(updatePositionWithBearing(asset.id, roadLink))
        massTransitStopDao.updateAssetLastModified(asset.id, username)
        massTransitStopDao.updateAssetProperties(asset.id, verifiedProperties.toSeq)
        massTransitStopDao.updateTextPropertyValue(asset.id, MassTransitStopOperations.LiViIdentifierPublicId, liviId)
        updateAdministrativeClassValue(asset.id, roadLink.administrativeClass)

        val persistedAsset = fetchAsset(asset.id)

        updateTierekisteriBusStop(persistedAsset, roadLink, liviId)

        persistedAsset
      }
    }else{
      val newLiviId = toLiviId.format(asset.nationalId)
      optionalPosition.map(updatePositionWithBearing(asset.id, roadLink))
      massTransitStopDao.updateAssetLastModified(asset.id, username)
      massTransitStopDao.updateAssetProperties(asset.id, verifiedProperties.toSeq)
      massTransitStopDao.updateTextPropertyValue(asset.id, MassTransitStopOperations.LiViIdentifierPublicId, newLiviId)
      updateAdministrativeClassValue(asset.id, roadLink.administrativeClass)

      val persistedAsset = fetchAsset(asset.id)

      createTierekisteriBusStop(persistedAsset, roadLink, newLiviId)

      persistedAsset
    }
  }

  override def delete(asset: PersistedMassTransitStop): Unit = {
    super.delete(asset)

    val liviId = getLiviIdValue(asset.propertyData).getOrElse(throw new NoSuchElementException)
    deleteTierekisteriBusStop(liviId)
  }

  private def getLiviIdValue(properties: Seq[AbstractProperty]) = {
    properties.find(_.publicId == MassTransitStopOperations.LiViIdentifierPublicId).flatMap(prop => prop.values.headOption).map(_.propertyValue)
  }

  private def calculateMovedDistance(asset: PersistedMassTransitStop, optionalPosition: Option[Position]): Double = {
    optionalPosition match {
      case Some(position) =>
        val assetPoint = Point(asset.lon, asset.lat)
        val newPoint = Point(position.lon, position.lat)
        assetPoint.distance2DTo(newPoint)
      case _ => 0
    }
  }

  /**
    * Verify if the stop is relevant to Tierekisteri: Must be non-virtual and must be administered by ELY or HSL.
    * Convenience method
    *
    */
  private def isStoredInTierekisteri(properties: Seq[AbstractProperty], administrativeClass: Option[AdministrativeClass]): Boolean = {
    val administrationProperty = properties.find(_.publicId == MassTransitStopOperations.AdministratorInfoPublicId)
    val stopType = properties.find(pro => pro.publicId == MassTransitStopOperations.MassTransitStopTypePublicId)
    val elyAdministrated = administrationProperty.exists(_.values.headOption.exists(_.propertyValue == MassTransitStopOperations.CentralELYPropertyValue))
    val isVirtualStop = stopType.exists(_.values.exists(_.propertyValue == MassTransitStopOperations.VirtualBusStopPropertyValue))
    val isHSLAdministrated =  administrationProperty.exists(_.values.headOption.exists(_.propertyValue == MassTransitStopOperations.HSLPropertyValue))
    val isAdminClassState = administrativeClass.contains(State)
    !isVirtualStop && (elyAdministrated || (isHSLAdministrated && isAdminClassState))
  }

  //  @throws(classOf[TierekisteriClientException])
  private def expireMassTransitStop(username: String, liviId: String, persistedStop: PersistedMassTransitStop) = {
    massTransitStopDao.expireMassTransitStop(username, persistedStop.id)
    //TODO This need to be changed because if after expire the asset, the update in OTH side fails the we are not doing rollback
    expireTierekisteriBusStop(persistedStop, liviId, username)
  }

  /**
    * Override the properties values passed as parameter using override operations
    *
    * @param tierekisteriStop         Tierekisteri Asset
    * @param persistedMassTransitStop Asset properties
    * @return Sequence of overridden properties
    */
  private def enrichWithTierekisteriInfo(persistedMassTransitStop: PersistedMassTransitStop, tierekisteriStop: TierekisteriMassTransitStop): PersistedMassTransitStop = {
    val overridePropertyValueOperations: Seq[(TierekisteriMassTransitStop, Property) => Property] = Seq(
      setEquipments,
      setTextPropertyValueIfEmpty(MassTransitStopOperations.nameFiPublicId, { ta => ta.nameFi.getOrElse("") }),
      setTextPropertyValueIfEmpty(MassTransitStopOperations.nameSePublicId, { ta => ta.nameSe.getOrElse("") })
      //In the future if we need to override some property just add here the operation
    )

    persistedMassTransitStop.copy(propertyData = persistedMassTransitStop.propertyData.map {
      property =>
        overridePropertyValueOperations.foldLeft(property) { case (prop, operation) =>
          operation(tierekisteriStop, prop)
        }
    })
  }

  private def setTextPropertyValueIfEmpty(publicId: String, getValue: TierekisteriMassTransitStop => String)(tierekisteriStop: TierekisteriMassTransitStop, property: Property): Property = {
    if (property.publicId == publicId && property.values.isEmpty) {
      val propertyValueString = getValue(tierekisteriStop)
      property.copy(values = Seq(new PropertyValue(propertyValueString, Some(propertyValueString))))
    } else {
      property
    }
  }

  /**
    * Override property values of all equipment properties
    *
    * @param tierekisteriStop Tierekisteri Asset
    * @param property         Asset property
    * @return Property passed as parameter if have no match with equipment property or property overriden with tierekisteri values
    */
  private def setEquipments(tierekisteriStop: TierekisteriMassTransitStop, property: Property) = {
    if (tierekisteriStop.equipments.isEmpty) {
      property
    } else {
      val equipment = Equipment.fromPublicId(property.publicId)
      val existence = tierekisteriStop.equipments.get(equipment)
      existence.isEmpty || !equipment.isMaster match {
        case true => property
        case false =>
          val propertyValueString = existence.get.propertyValue.toString
          val propertyOverrideValue = massTransitStopEnumeratedPropertyValues.
            get(property.publicId).get.find(_.propertyValue == propertyValueString).get
          property.copy(values = Seq(propertyOverrideValue))
      }
    }
  }

  private def mapTierekisteriBusStop(persistedStop: PersistedMassTransitStop, liviId: String, expire: Option[Date] = None, roadLinkOption: Option[RoadLink] = None): TierekisteriMassTransitStop ={
    val road = roadLinkOption match {
      case Some(roadLink) =>
        roadLink.roadNumber match {
          case Some(str) => Try(str.toString.toInt).toOption
          case _ => None
        }
      case _ => None
    }
    val (address, roadSide) = geometryTransform.resolveAddressAndLocation(Point(persistedStop.lon, persistedStop.lat), persistedStop.bearing.get, persistedStop.mValue, persistedStop.linkId, persistedStop.validityDirection.get, road = road)

    TierekisteriBusStopMarshaller.toTierekisteriMassTransitStop(persistedStop, address, Some(roadSide), expire, Some(liviId))
  }

  private def createTierekisteriBusStop(persistedStop: PersistedMassTransitStop, roadLink: RoadLink, liviId: String): Unit =
    if(tierekisteriClient.isTREnabled) tierekisteriClient.createMassTransitStop(mapTierekisteriBusStop(persistedStop, liviId, roadLinkOption = Some(roadLink)))

  private def updateTierekisteriBusStop(persistedStop: PersistedMassTransitStop, roadLink: RoadLink, liviId: String): Unit =
    if(tierekisteriClient.isTREnabled) tierekisteriClient.updateMassTransitStop(mapTierekisteriBusStop(persistedStop, liviId, roadLinkOption = Some(roadLink)), Some(liviId))

  private def expireTierekisteriBusStop(persistedStop: PersistedMassTransitStop, liviId: String, username: String): Unit  =
    if(tierekisteriClient.isTREnabled) tierekisteriClient.updateMassTransitStop(mapTierekisteriBusStop(persistedStop, liviId, expire = Some(new Date())), Some(liviId), Some(username))

  private def deleteTierekisteriBusStop(liviId: String): Unit  =
    if(tierekisteriClient.isTREnabled) tierekisteriClient.deleteMassTransitStop(liviId)

}
