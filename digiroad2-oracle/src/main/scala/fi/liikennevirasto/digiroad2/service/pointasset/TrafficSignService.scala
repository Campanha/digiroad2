package fi.liikennevirasto.digiroad2.service.pointasset

import fi.liikennevirasto.digiroad2.PointAssetFiller.AssetAdjustment
import fi.liikennevirasto.digiroad2.{asset, _}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.asset.SideCode._
import fi.liikennevirasto.digiroad2.client.tierekisteri.TRTrafficSignType
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.pointasset.{OracleTrafficSignDao, PersistedTrafficSign}
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset.{ManoeuvreCreationException, ManoeuvreProvider}
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import org.slf4j.LoggerFactory

case class IncomingTrafficSign(lon: Double, lat: Double, linkId: Long, propertyData: Set[SimpleProperty], validityDirection: Int, bearing: Option[Int]) extends IncomingPointAsset

sealed trait TrafficSignTypeGroup {
  def value: Int
}
object TrafficSignTypeGroup {
  val values = Set(Unknown, SpeedLimits, PedestrianCrossing, MaximumRestrictions, GeneralWarningSigns, ProhibitionsAndRestrictions)

  def apply(intValue: Int): TrafficSignTypeGroup = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  case object SpeedLimits extends TrafficSignTypeGroup { def value = 1  }
  case object PedestrianCrossing extends TrafficSignTypeGroup { def value = 2 }
  case object MaximumRestrictions extends TrafficSignTypeGroup { def value = 3 }
  case object GeneralWarningSigns extends TrafficSignTypeGroup { def value = 4 }
  case object ProhibitionsAndRestrictions extends TrafficSignTypeGroup { def value = 5 }
  case object Unknown extends TrafficSignTypeGroup { def value = 99 }
}

sealed trait TrafficSignType {
  def value: Int
  def group: TrafficSignTypeGroup
}
object TrafficSignType {
  val values = Set(Unknown, SpeedLimit, EndSpeedLimit, SpeedLimitZone, EndSpeedLimitZone, UrbanArea, EndUrbanArea, PedestrianCrossing, MaximumLength, Warning, NoLeftTurn,
    NoRightTurn, NoUTurn, ClosedToAllVehicles, NoPowerDrivenVehicles, NoLorriesAndVans, NoVehicleCombinations, NoAgriculturalVehicles, NoMotorCycles, NoMotorSledges,
    NoVehiclesWithDangerGoods, NoBuses, NoMopeds, NoCyclesOrMopeds, NoPedestrians, NoPedestriansCyclesMopeds, NoRidersOnHorseback, NoEntry, OvertakingProhibited,
    EndProhibitionOfOvertaking, NoWidthExceeding, MaxHeightExceeding, MaxLadenExceeding, MaxMassCombineVehiclesExceeding, MaxTonsOneAxleExceeding, MaxTonsOnBogieExceeding,
    WRightBend, WLeftBend, WSeveralBendsRight, WSeveralBendsLeft, WDangerousDescent, WSteepAscent, WUnevenRoad, WChildren, TelematicSpeedLimit)

  def apply(intValue: Int): TrafficSignType = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  case object SpeedLimit extends TrafficSignType { def value = 1;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object EndSpeedLimit extends TrafficSignType { def value = 2;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object SpeedLimitZone extends TrafficSignType { def value = 3;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object EndSpeedLimitZone extends TrafficSignType { def value = 4;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object UrbanArea extends TrafficSignType { def value = 5;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object EndUrbanArea extends TrafficSignType { def value = 6;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object PedestrianCrossing extends TrafficSignType { def value = 7;  def group = TrafficSignTypeGroup.PedestrianCrossing; }
  case object MaximumLength extends TrafficSignType { def value = 8;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object Warning extends TrafficSignType { def value = 9;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object NoLeftTurn extends TrafficSignType { def value = 10;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoRightTurn extends TrafficSignType { def value = 11;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoUTurn extends TrafficSignType { def value = 12;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object ClosedToAllVehicles extends TrafficSignType { def value = 13;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoPowerDrivenVehicles extends TrafficSignType { def value = 14;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoLorriesAndVans extends TrafficSignType { def value = 15;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoVehicleCombinations extends TrafficSignType { def value = 16;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoAgriculturalVehicles extends TrafficSignType { def value = 17;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoMotorCycles extends TrafficSignType { def value = 18;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoMotorSledges extends TrafficSignType { def value = 19;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoVehiclesWithDangerGoods extends TrafficSignType { def value = 20;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoBuses extends TrafficSignType { def value = 21;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoMopeds extends TrafficSignType { def value = 22;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoCyclesOrMopeds extends TrafficSignType { def value = 23;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoPedestrians extends TrafficSignType { def value = 24;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoPedestriansCyclesMopeds extends TrafficSignType { def value = 25;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoRidersOnHorseback extends TrafficSignType { def value = 26;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoEntry extends TrafficSignType { def value = 27;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object OvertakingProhibited extends TrafficSignType { def value = 28;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object EndProhibitionOfOvertaking extends TrafficSignType { def value = 29;  def group = TrafficSignTypeGroup.ProhibitionsAndRestrictions; }
  case object NoWidthExceeding extends TrafficSignType { def value = 30;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object MaxHeightExceeding extends TrafficSignType { def value = 31;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object MaxLadenExceeding extends TrafficSignType { def value = 32;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object MaxMassCombineVehiclesExceeding extends TrafficSignType { def value = 33;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object MaxTonsOneAxleExceeding extends TrafficSignType { def value = 34;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object MaxTonsOnBogieExceeding extends TrafficSignType { def value = 35;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object WRightBend extends TrafficSignType { def value = 36;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object WLeftBend extends TrafficSignType { def value = 37;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object WSeveralBendsRight extends TrafficSignType { def value = 38;  def group = TrafficSignTypeGroup.GeneralWarningSigns ; }
  case object WSeveralBendsLeft extends TrafficSignType { def value = 39;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object WDangerousDescent extends TrafficSignType { def value = 40;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object WSteepAscent extends TrafficSignType { def value = 41;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object WUnevenRoad extends TrafficSignType { def value = 42;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object WChildren extends TrafficSignType { def value = 43;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object TelematicSpeedLimit extends TrafficSignType { def value = 44;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object Unknown extends TrafficSignType { def value = 99;  def group = TrafficSignTypeGroup.Unknown; }
}

class TrafficSignService(val roadLinkService: RoadLinkService, val userProvider: UserProvider, eventBusImpl: DigiroadEventBus) extends PointAssetOperations {

  def eventBus: DigiroadEventBus = eventBusImpl
  type IncomingAsset = IncomingTrafficSign
  type PersistedAsset = PersistedTrafficSign
  val logger = LoggerFactory.getLogger(getClass)

  override def typeId: Int = 300

  override def setAssetPosition(asset: IncomingTrafficSign, geometry: Seq[Point], mValue: Double): IncomingTrafficSign = {
    GeometryUtils.calculatePointFromLinearReference(geometry, mValue) match {
      case Some(point) =>
        asset.copy(lon = point.x, lat = point.y)
      case _ =>
        asset
    }
  }

  private val typePublicId = "trafficSigns_type"
  private val valuePublicId = "trafficSigns_value"
  private val infoPublicId = "trafficSigns_info"

  private val additionalInfoTypeGroups = Set(TrafficSignTypeGroup.GeneralWarningSigns, TrafficSignTypeGroup.ProhibitionsAndRestrictions)

  override def fetchPointAssets(queryFilter: String => String, roadLinks: Seq[RoadLinkLike]): Seq[PersistedTrafficSign] = OracleTrafficSignDao.fetchByFilter(queryFilter)

  override def setFloating(persistedAsset: PersistedTrafficSign, floating: Boolean): PersistedTrafficSign = {
    persistedAsset.copy(floating = floating)
  }

  override def create(asset: IncomingTrafficSign, username: String, roadLink: RoadLink): Long = {
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(asset.lon, asset.lat), roadLink.geometry)
    withDynTransaction {
      val id = OracleTrafficSignDao.create(setAssetPosition(asset, roadLink.geometry, mValue), mValue, username, roadLink.municipalityCode, VVHClient.createVVHTimeStamp(), roadLink.linkSource)
      if (belongsToTurnRestriction(asset)) {
        eventBus.publish("manoeuvre:create", ManoeuvreProvider(getPersistedAssetsByIdsWithoutTransaction(Set(id)).head, roadLink))
      }
      id
    }
  }

  def belongsToTurnRestriction(asset: IncomingTrafficSign)  = {
    val turnRestrictionsGroup =  Seq(/*TrafficSignType.NoUTurn, */TrafficSignType.NoRightTurn, TrafficSignType.NoLeftTurn)
    turnRestrictionsGroup.contains(asset.propertyData.find(p => p.publicId == "trafficSigns_type").get.values.headOption.map(t => TrafficSignType(t.propertyValue.toInt)).get)
  }

  def createFloating(asset: IncomingTrafficSign, username: String, municipality: Int): Long = {
    withDynTransaction {
      OracleTrafficSignDao.createFloating(asset, 0, username, municipality, VVHClient.createVVHTimeStamp(), LinkGeomSource.Unknown, floating = true)
    }
  }

  override def update(id: Long, updatedAsset: IncomingTrafficSign, roadLink: RoadLink, username: String): Long = {
    withDynTransaction {
      updateWithoutTransaction(id, updatedAsset, roadLink, username, None, None)
    }
  }

  def updateWithoutTransaction(id: Long, updatedAsset: IncomingTrafficSign, roadLink: RoadLink, username: String, mValue: Option[Double], vvhTimeStamp: Option[Long]): Long = {
    val value = mValue.getOrElse(GeometryUtils.calculateLinearReferenceFromPoint(Point(updatedAsset.lon, updatedAsset.lat), roadLink.geometry))
    getPersistedAssetsByIdsWithoutTransaction(Set(id)).headOption.getOrElse(throw new NoSuchElementException("Asset not found")) match {
      case old if old.bearing != updatedAsset.bearing || (old.lat != updatedAsset.lat || old.lon != updatedAsset.lon) || old.validityDirection != updatedAsset.validityDirection =>
        expireWithoutTransaction(id)
        val newId = OracleTrafficSignDao.create(setAssetPosition(updatedAsset, roadLink.geometry, value), value, username, roadLink.municipalityCode, vvhTimeStamp.getOrElse(VVHClient.createVVHTimeStamp()), roadLink.linkSource, old.createdBy, old.createdAt)
        if(belongsToTurnRestriction(updatedAsset)) {
          println(s"Creating manoeuvre on linkId: ${roadLink.linkId} from import traffic sign with id $newId" )
          eventBus.publish("manoeuvre:expire", id)
          eventBus.publish("manoeuvre:create", ManoeuvreProvider(getPersistedAssetsByIdsWithoutTransaction(Set(newId)).head, roadLink))
        }
        newId
      case _ =>
        OracleTrafficSignDao.update(id, setAssetPosition(updatedAsset, roadLink.geometry, value), value, roadLink.municipalityCode, username, Some(vvhTimeStamp.getOrElse(VVHClient.createVVHTimeStamp())), roadLink.linkSource)
    }
  }

  override def getByBoundingBox(user: User, bounds: BoundingRectangle): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(bounds)
    super.getByBoundingBox(user, bounds, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation))
  }

  private def createOperation(asset: PersistedAsset, adjustment: AssetAdjustment): PersistedAsset = {
    createPersistedAsset(asset, adjustment)
  }

  private def adjustmentOperation(persistedAsset: PersistedAsset, adjustment: AssetAdjustment, roadLink: RoadLink): Long = {
    val updated = IncomingTrafficSign(adjustment.lon, adjustment.lat, adjustment.linkId,
      persistedAsset.propertyData.map(prop => SimpleProperty(prop.publicId, prop.values)).toSet,
      persistedAsset.validityDirection, persistedAsset.bearing)

    updateWithoutTransaction(adjustment.assetId, updated, roadLink,
      "vvh_generated", Some(adjustment.mValue), Some(adjustment.vvhTimeStamp))
  }

  override def getByMunicipality(municipalityCode: Int): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipalityCode)
    val mapRoadLinks = roadLinks.map(l => l.linkId -> l).toMap
    getByMunicipality(municipalityCode, mapRoadLinks, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation))
  }

  private def createPersistedAsset[T](persistedStop: PersistedAsset, asset: AssetAdjustment) = {
    new PersistedAsset(asset.assetId, asset.linkId, asset.lon, asset.lat,
      asset.mValue, asset.floating, persistedStop.vvhTimeStamp, persistedStop.municipalityCode, persistedStop.propertyData, persistedStop.createdBy,
      persistedStop.createdAt, persistedStop.modifiedBy, persistedStop.modifiedAt, persistedStop.validityDirection, persistedStop.bearing,
      persistedStop.linkSource)
  }

  def getAssetValidityDirection(bearing: Int): Int = {
    bearing >= 270 || bearing < 90 match {
      case true => AgainstDigitizing.value
      case false => TowardsDigitizing.value
    }
  }

  def getTrafficSignValidityDirection(assetLocation: Point, geometry: Seq[Point]): Int = {

    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(assetLocation.x, assetLocation.y, 0), geometry)
    val roadLinkPoint = GeometryUtils.calculatePointFromLinearReference(geometry, mValue)
    val linkBearing = GeometryUtils.calculateBearing(geometry)

    val lonDifference = assetLocation.x - roadLinkPoint.get.x
    val latDifference = assetLocation.y - roadLinkPoint.get.y

    (latDifference <= 0 && linkBearing <= 90) || (latDifference >= 0 && linkBearing > 270) match {
      case true => TowardsDigitizing.value
      case false => AgainstDigitizing.value
    }
  }

  def getAssetBearing(validityDirection: Int, geometry: Seq[Point]): Int = {
    val linkBearing = GeometryUtils.calculateBearing(geometry)
    GeometryUtils.calculateActualBearing(validityDirection, Some(linkBearing)).get
  }

  private def generateProperties(tRTrafficSignType: TRTrafficSignType, value: Any) = {
    val signValue = value.toString
    val trafficType = tRTrafficSignType.trafficSignType
    val typeProperty = SimpleProperty(typePublicId, Seq(PropertyValue(trafficType.value.toString)))
    val valueProperty = additionalInfoTypeGroups.exists(group => group == trafficType.group) match {
      case true => SimpleProperty(infoPublicId, Seq(PropertyValue(signValue)))
      case _ => SimpleProperty(valuePublicId, Seq(PropertyValue(signValue)))
    }

    Set(typeProperty, valueProperty)
  }

  def createFromCoordinates(lon: Long, lat: Long, trafficSignType: TRTrafficSignType, value: Option[Int], twoSided: Option[Boolean], trafficDirection: TrafficDirection, bearing: Option[Int]): Long = {
    val roadLinks = roadLinkService.getClosestRoadlinkForCarTrafficFromVVH(userProvider.getCurrentUser(), Point(lon, lat))
    if (roadLinks.nonEmpty) {
      val closestLink = roadLinks.minBy(r => GeometryUtils.minimumDistance(Point(lon, lat), r.geometry))
      val (vvhRoad, municipality) = (roadLinks.filter(_.administrativeClass != State), closestLink.municipalityCode)
      if (vvhRoad.isEmpty || vvhRoad.size > 1) {
        val asset = IncomingTrafficSign(lon, lat, 0, generateProperties(trafficSignType, value.getOrElse("")), 0, None)
        createFloating(asset, userProvider.getCurrentUser().username, municipality)
      }
      else {
        roadLinkService.getRoadLinkFromVVH(closestLink.linkId).map { link =>
          val validityDirection =
            bearing match {
              case Some(assetBearing) if twoSided.getOrElse(false) => BothDirections.value
              case Some(assetBearing) => getAssetValidityDirection(assetBearing)
              case None if twoSided.getOrElse(false) => BothDirections.value
              case _ => getTrafficSignValidityDirection(Point(lon, lat), link.geometry)
            }
          val asset = IncomingTrafficSign(lon, lat, link.linkId, generateProperties(trafficSignType, value.getOrElse("")), validityDirection, Some(GeometryUtils.calculateBearing(link.geometry)))
          create(asset, userProvider.getCurrentUser().username, link)
        }.getOrElse(0L)
      }
    } else {
      0L
    }
  }

  def getTrafficSignByRadius(position: Point, meters: Int, optGroupType: Option[TrafficSignTypeGroup]): Seq[PersistedTrafficSign] = {
    val assets = OracleTrafficSignDao.fetchByRadius(position, meters)
    optGroupType match {
      case Some(groupType) => assets.filter(asset => TrafficSignTypeGroup.apply(asset.propertyData.find(p => p.publicId == "trafficSigns_type").get.values.head.propertyValue.toInt) == groupType)
      case _ => assets
    }
  }

  def getTrafficSignsWithTrafficRestrictions(newTransaction: Boolean = true): Seq[PersistedTrafficSign] = {
    val enumeratedValueIds = getRestrictionsEnumeratedValues(newTransaction)
    if(newTransaction)
        withDynSession {
          OracleTrafficSignDao.fetchByTurningRestrictions(enumeratedValueIds)
        }
    else {
      OracleTrafficSignDao.fetchByTurningRestrictions(enumeratedValueIds)
    }
  }


  private def getRestrictionsEnumeratedValues(newTransaction: Boolean = true): Seq[Long] = {
    if(newTransaction)
      withDynSession {
        OracleTrafficSignDao.fetchEnumeratedValueIds(Seq(TrafficSignType.NoLeftTurn, TrafficSignType.NoRightTurn/*, TrafficSignType.NoUTurn*/))
      }
    else {
      OracleTrafficSignDao.fetchEnumeratedValueIds(Seq(TrafficSignType.NoLeftTurn, TrafficSignType.NoRightTurn/*, TrafficSignType.NoUTurn*/))
    }
  }

  override def expire(id: Long, username: String): Long = {
    withDynSession {
      expireWithoutTransaction(id, username)
    }
    eventBus.publish("manoeuvre:expire", id)
    id
  }

  def expireAssetWithoutTransaction(id: Long, username: String): Long = {
    expireWithoutTransaction(id)
    eventBus.publish("manoeuvre:expire", id)
    id
  }
}
