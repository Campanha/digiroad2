package fi.liikennevirasto.digiroad2.service.pointasset

import fi.liikennevirasto.digiroad2.asset.{SideCode, UnknownLinkType}
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, VVHClient}
import fi.liikennevirasto.digiroad2.dao.{MunicipalityDao, OracleAssetDao}
import fi.liikennevirasto.digiroad2.dao.linearasset.OracleLinearAssetDao
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller.{ChangeSet, MValueAdjustment, SideCodeAdjustment}
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset.{LinearAssetOperations, LinearAssetTypes, Measures}
import fi.liikennevirasto.digiroad2.util.{LinearAssetUtils, PolygonTools}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils}
import org.joda.time.DateTime

import scala.slick.jdbc.{StaticQuery => Q}

class PavingService(roadLinkServiceImpl: RoadLinkService, eventBusImpl: DigiroadEventBus) extends LinearAssetOperations {
  override def roadLinkService: RoadLinkService = roadLinkServiceImpl
  override def dao: OracleLinearAssetDao = new OracleLinearAssetDao(roadLinkServiceImpl.vvhClient, roadLinkServiceImpl)
  override def municipalityDao: MunicipalityDao = new MunicipalityDao
  override def eventBus: DigiroadEventBus = eventBusImpl
  override def vvhClient: VVHClient = roadLinkServiceImpl.vvhClient
  override def polygonTools: PolygonTools = new PolygonTools()
  override def assetDao: OracleAssetDao = new OracleAssetDao

  override def getUncheckedLinearAssets(areas: Option[Set[Int]]) = throw new UnsupportedOperationException("Not supported method")

  val PavingAssetTypeId = 110

  override protected def getByRoadLinks(typeId: Int, roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo]): Seq[PieceWiseLinearAsset] = {
    val linkIds = roadLinks.map(_.linkId)
    val mappedChanges = LinearAssetUtils.getMappedChanges(changes)
    val removedLinkIds = LinearAssetUtils.deletedRoadLinkIds(mappedChanges, linkIds.toSet)
    val existingAssets =
      withDynTransaction {
        dao.fetchLinearAssetsByLinkIds(PavingAssetTypeId, linkIds ++ removedLinkIds, LinearAssetTypes.numericValuePropertyId).filterNot(_.expired)
      }

    val timing = System.currentTimeMillis

    val (assetsOnChangedLinks, assetsWithoutChangedLinks) = existingAssets.partition(a => LinearAssetUtils.newChangeInfoDetected(a, mappedChanges))

    val projectableTargetRoadLinks = roadLinks.filter(rl => rl.linkType.value == UnknownLinkType.value || rl.isCarTrafficRoad)
    val (expiredIds, newAndUpdatedPavingAssets) = getPavingAssetChanges(existingAssets, roadLinks, changes, typeId)

    val initChangeSet = ChangeSet(droppedAssetIds = Set.empty[Long],
      expiredAssetIds = existingAssets.filter(asset => removedLinkIds.contains(asset.linkId)).map(_.id).toSet ++ expiredIds,
      adjustedMValues = newAndUpdatedPavingAssets.filter(_.id != 0).map( a => MValueAdjustment(a.id, a.linkId, a.startMeasure, a.endMeasure)),
      adjustedSideCodes = Seq.empty[SideCodeAdjustment])

    val combinedAssets = existingAssets.filterNot(a => expiredIds.contains(a.id) || newAndUpdatedPavingAssets.exists(_.id == a.id) || assetsWithoutChangedLinks.exists(_.id == a.id)
    ) ++ newAndUpdatedPavingAssets

    val (projectedAssets, changedSet) = fillNewRoadLinksWithPreviousAssetsData(projectableTargetRoadLinks, combinedAssets, assetsOnChangedLinks, changes, initChangeSet)

    val newAssets = newAndUpdatedPavingAssets.filterNot(a => projectedAssets.exists(f => f.linkId == a.linkId)) ++ projectedAssets

    if (newAssets.nonEmpty) {
      logger.info("Transferred %d assets in %d ms ".format(newAssets.length, System.currentTimeMillis - timing))
    }
    val groupedAssets = (existingAssets.filterNot(a => expiredIds.contains(a.id) || newAssets.exists(_.linkId == a.linkId)) ++ newAssets ++ assetsWithoutChangedLinks).groupBy(_.linkId)
    val (filledTopology, changeSet) = NumericalLimitFiller.fillTopology(roadLinks, groupedAssets, typeId, Some(changedSet))

    eventBus.publish("linearAssets:update", changeSet)
    eventBus.publish("paving:saveProjectedPaving", newAssets.filter(_.id == 0L))

    filledTopology
  }

  def getPavingAssetChanges(existingLinearAssets: Seq[PersistedLinearAsset], roadLinks: Seq[RoadLink],
                            changeInfos: Seq[ChangeInfo], typeId: Long): (Set[Long], Seq[PersistedLinearAsset]) = {

    //Group last vvhchanges by link id
    val lastChanges = changeInfos.filter(_.newId.isDefined).groupBy(_.newId.get).mapValues(c => c.maxBy(_.vvhTimeStamp))

    //Map all existing assets by roadlink and changeinfo
    val changedAssets = lastChanges.map{
      case (linkId, changeInfo) =>
        (roadLinks.find(_.linkId == linkId), changeInfo, existingLinearAssets.filter(_.linkId == linkId))
    }

    /* Note: This uses isNotPaved that excludes "unknown" pavement status. In OTH unknown means
    *  "no pavement" but in case OTH has pavement info with value 1 then VVH "unknown" should not affect OTH.
    *  Additionally, should there be an override that is later fixed we let the asset expire here as no
    *  override is needed anymore.
    */
    val expiredAssetsIds = changedAssets.flatMap {
      case (Some(roadlink), changeInfo, assets) =>
        if (roadlink.isNotPaved && assets.nonEmpty)
          assets.filter(_.vvhTimeStamp < changeInfo.vvhTimeStamp).map(_.id)
        else
          List()
      case _ =>
        List()
    }.toSet[Long]

    /* Note: This will not change anything if asset is stored using value None (null in database)
    *  This is the intended consequence as it enables the UI to write overrides to VVH pavement info */
    val newAndUpdatedAssets = changedAssets.flatMap{
      case (Some(roadlink), changeInfo, assets) =>
        if(roadlink.isPaved)
          if (assets.isEmpty)
            Some(PersistedLinearAsset(0L, roadlink.linkId, SideCode.BothDirections.value, Some(NumericValue(1)), 0,
              GeometryUtils.geometryLength(roadlink.geometry), None, None, None, None, false,
              LinearAssetTypes.PavingAssetTypeId, changeInfo.vvhTimeStamp, None, linkSource = roadlink.linkSource, None, None, None))
          else
            assets.filterNot(a => expiredAssetsIds.contains(a.id) ||
              (a.value.isEmpty && a.vvhTimeStamp >= changeInfo.vvhTimeStamp)
            ).map(a => a.copy(vvhTimeStamp = changeInfo.vvhTimeStamp, value=Some(NumericValue(1)),
              startMeasure=0.0, endMeasure=roadlink.length))
        else
          None
      case _ =>
        None
    }.toSeq

    (expiredAssetsIds, newAndUpdatedAssets)
  }

  /*
   * Creates new linear assets and updates existing. Used by the Digiroad2Context.LinearAssetSaveProjected actor.
   */
  override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset]): Unit ={
    if (newLinearAssets.nonEmpty)
      logger.info("Saving projected paved assets")

    val (toInsert, toUpdate) = newLinearAssets.partition(_.id == 0L)
    withDynTransaction {
      val roadLinks = roadLinkService.getRoadLinksAndComplementariesFromVVH(newLinearAssets.map(_.linkId).toSet, newTransaction = false)

      if(toUpdate.nonEmpty) {
        val persisted = dao.fetchLinearAssetsByIds(toUpdate.map(_.id).toSet, LinearAssetTypes.numericValuePropertyId).groupBy(_.id)
        updateProjected(toUpdate, persisted)

        if (newLinearAssets.nonEmpty)
          logger.info("Updated ids/linkids " + toUpdate.map(a => (a.id, a.linkId)))
      }

      toInsert.foreach{ linearAsset =>
        val id = dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
          Measures(linearAsset.startMeasure, linearAsset.endMeasure), linearAsset.createdBy.getOrElse(LinearAssetTypes.VvhGenerated), linearAsset.vvhTimeStamp, getLinkSource(roadLinks.find(_.linkId == linearAsset.linkId)))
        linearAsset.value match {
          case Some(NumericValue(intValue)) =>
            dao.insertValue(id, LinearAssetTypes.numericValuePropertyId, intValue)
          case _ => None
        }
      }
      if (newLinearAssets.nonEmpty)
        logger.info("Added assets for linkids " + toInsert.map(_.linkId))
    }
  }

  override protected def updateProjected(toUpdate: Seq[PersistedLinearAsset], persisted: Map[Long, Seq[PersistedLinearAsset]]) = {
    def valueChanged(assetToPersist: PersistedLinearAsset, persistedLinearAsset: Option[PersistedLinearAsset]) = {
      !persistedLinearAsset.exists(_.value == assetToPersist.value)
    }
    toUpdate.foreach { linearAsset =>
      val persistedLinearAsset = persisted.getOrElse(linearAsset.id, Seq()).headOption
      val id = linearAsset.id
      if (valueChanged(linearAsset, persistedLinearAsset)) {
        linearAsset.value match {
          case Some(NumericValue(intValue)) =>
            dao.updateValue(id, intValue, LinearAssetTypes.numericValuePropertyId, LinearAssetTypes.VvhGenerated)
          case _ => None
        }
      }
    }
  }

  override protected def updateWithoutTransaction(ids: Seq[Long], value: Value, username: String, measures: Option[Measures] = None, vvhTimeStamp: Option[Long] = None, sideCode: Option[Int] = None, informationSource: Option[Int] = None): Seq[Long] = {
    if (ids.isEmpty)
      return ids

    ids.flatMap { id =>
      updateValueByExpiration(id, value.asInstanceOf[NumericValue], LinearAssetTypes.numericValuePropertyId, username, measures, vvhTimeStamp, sideCode)
    }
  }

  override def create(newLinearAssets: Seq[NewLinearAsset], typeId: Int, username: String, vvhTimeStamp: Long = vvhClient.roadLinkData.createVVHTimeStamp()): Seq[Long] = {
    withDynTransaction {
      val roadlinks = roadLinkService.getRoadLinksAndComplementariesFromVVH(newLinearAssets.map(_.linkId).toSet, false)
      newLinearAssets.flatMap{ newAsset =>
        if (newAsset.value.toJson == 1) {
          Some(createWithoutTransaction(typeId, newAsset.linkId, newAsset.value, newAsset.sideCode, Measures(newAsset.startMeasure, newAsset.endMeasure), username, vvhTimeStamp, roadlinks.find(_.linkId == newAsset.linkId)))
        } else {
          None
        }
      }
    }
  }

  override protected def createWithoutTransaction(typeId: Int, linkId: Long, value: Value, sideCode: Int, measures: Measures, username: String, vvhTimeStamp: Long, roadLink: Option[RoadLinkLike], fromUpdate: Boolean = false,
                                         createdByFromUpdate: Option[String] = Some(""),
                                         createdDateTimeFromUpdate: Option[DateTime] = Some(DateTime.now()), verifiedBy: Option[String] = None, informationSource: Option[Int] = None): Long = {
    val id = dao.createLinearAsset(typeId, linkId, expired = false, sideCode, measures, username,
      vvhTimeStamp, getLinkSource(roadLink), fromUpdate, createdByFromUpdate, createdDateTimeFromUpdate)
    value match {
      case NumericValue(intValue) =>
        dao.insertValue(id, LinearAssetTypes.numericValuePropertyId, intValue)
      case _ => None
    }
    id
  }

  override protected def updateValueByExpiration(assetId: Long, valueToUpdate: Value, valuePropertyId: String, username: String, measures: Option[Measures], vvhTimeStamp: Option[Long], sideCode: Option[Int], informationSource: Option[Int] = None): Option[Long] = {
    //Get Old Asset
    val oldAsset =
    valueToUpdate match {
      case NumericValue(intValue) =>
        dao.fetchLinearAssetsByIds(Set(assetId), valuePropertyId).head
      case _ => return None
    }

    val measure = measures.getOrElse(Measures(oldAsset.startMeasure, oldAsset.endMeasure))

    //Expire the old asset
    dao.updateExpiration(assetId, expired = true, username)
    val roadLink = roadLinkService.getRoadLinkAndComplementaryFromVVH(oldAsset.linkId, newTransaction = false)
    if (valueToUpdate.toJson == 0){
      Seq(Measures(oldAsset.startMeasure, measure.startMeasure), Measures(measure.endMeasure, oldAsset.endMeasure)).map {
        m =>
          if (m.endMeasure - m.startMeasure > 0.01)
            createWithoutTransaction(oldAsset.typeId, oldAsset.linkId, valueToUpdate, sideCode.getOrElse(oldAsset.sideCode),
              m, username, vvhTimeStamp.getOrElse(vvhClient.roadLinkData.createVVHTimeStamp()), roadLink, true, oldAsset.createdBy, Some(oldAsset.createdDateTime.getOrElse(DateTime.now())))
      }
      Some(0L)
    }else {
      Some(createWithoutTransaction(oldAsset.typeId, oldAsset.linkId, valueToUpdate, sideCode.getOrElse(oldAsset.sideCode),
        measure, username, vvhTimeStamp.getOrElse(vvhClient.roadLinkData.createVVHTimeStamp()), roadLink, true, oldAsset.createdBy, Some(oldAsset.createdDateTime.getOrElse(DateTime.now()))))
    }
  }

  override def getPersistedAssetsByIds(typeId: Int, ids: Set[Long]): Seq[PersistedLinearAsset] = {
    withDynTransaction {
      dao.fetchLinearAssetsByIds(ids, LinearAssetTypes.getValuePropertyId(typeId))
    }
  }
}
