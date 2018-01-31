package fi.liikennevirasto.digiroad2.service.linearasset

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, VVHClient}
import fi.liikennevirasto.digiroad2.dao.{MunicipalityDao, OracleAssetDao}
import fi.liikennevirasto.digiroad2.dao.linearasset.{AssetLastModification, OracleLinearAssetDao}
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller.{ChangeSet, MValueAdjustment, SideCodeAdjustment}
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{LinearAssetUtils, PolygonTools}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils}
import org.joda.time.DateTime

class RoadWidthService(roadLinkServiceImpl: RoadLinkService, eventBusImpl: DigiroadEventBus) extends LinearAssetOperations {
  override def roadLinkService: RoadLinkService = roadLinkServiceImpl
  override def dao: OracleLinearAssetDao = new OracleLinearAssetDao(roadLinkServiceImpl.vvhClient, roadLinkServiceImpl)
  override def municipalityDao: MunicipalityDao = new MunicipalityDao
  override def eventBus: DigiroadEventBus = eventBusImpl
  override def vvhClient: VVHClient = roadLinkServiceImpl.vvhClient
  override def polygonTools: PolygonTools = new PolygonTools()
  override def assetDao: OracleAssetDao = new OracleAssetDao

  override def getUncheckedLinearAssets(areas: Option[Set[Int]]) = throw new UnsupportedOperationException("Not supported method")

  override protected def getByRoadLinks(typeId: Int, roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo]): Seq[PieceWiseLinearAsset] = {

      val linkIds = roadLinks.map(_.linkId)
      val mappedChanges = LinearAssetUtils.getMappedChanges(changes)
      val removedLinkIds = LinearAssetUtils.deletedRoadLinkIds(mappedChanges, linkIds.toSet)
      val existingAssets =
        withDynTransaction {
            dao.fetchLinearAssetsByLinkIds(LinearAssetTypes.RoadWidthAssetTypeId, linkIds ++ removedLinkIds, LinearAssetTypes.numericValuePropertyId)
        }

      val timing = System.currentTimeMillis
      val (assetsOnChangedLinks, assetsWithoutChangedLinks) = existingAssets.partition(a => LinearAssetUtils.newChangeInfoDetected(a, mappedChanges))

      val projectableTargetRoadLinks = roadLinks.filter(rl => rl.linkType.value == UnknownLinkType.value || rl.isCarTrafficRoad)

      val initChangeSet: ChangeSet = ChangeSet(droppedAssetIds = Set.empty[Long],
                                    expiredAssetIds = existingAssets.filter(asset => removedLinkIds.contains(asset.linkId)).map(_.id).toSet.filterNot(_ == 0L),
                                    adjustedMValues = Seq.empty[MValueAdjustment],
                                    adjustedSideCodes = Seq.empty[SideCodeAdjustment])

      val (projectedAssets, changedSetProjected) = fillNewRoadLinksWithPreviousAssetsData(projectableTargetRoadLinks,
        assetsOnChangedLinks, assetsOnChangedLinks, changes, initChangeSet)

      val (newRoadWidthAssets, changedSet) = getRoadWidthAssetChanges(existingAssets ++ projectedAssets, roadLinks, changes, (newAssetIds) => withDynTransaction {
          dao.fetchExpireAssetLastModificationsByLinkIds(LinearAssetTypes.RoadWidthAssetTypeId, newAssetIds)
      }, changedSetProjected)

      val newAssets = assetsWithoutChangedLinks ++ projectedAssets.filterNot(a =>
        newRoadWidthAssets.exists(b => b.linkId == a.linkId && b.sideCode == a.sideCode && b.startMeasure == a.startMeasure && b.endMeasure == a.endMeasure)) ++
        newRoadWidthAssets

      if (newAssets.nonEmpty) {
        logger.info("Transferred %d assets in %d ms ".format(newAssets.length, System.currentTimeMillis - timing))
      }

      val groupedAssets = (existingAssets.filterNot(a => changedSet.expiredAssetIds.contains(a.id) || newAssets.exists(_.linkId == a.linkId)) ++ newAssets).groupBy(_.linkId)
      val (filledTopology, changeSet) = NumericalLimitFiller.fillTopology(roadLinks, groupedAssets, typeId, Some(changedSet))

      eventBus.publish("roadWidth:update", changeSet)
      eventBus.publish("RoadWidth:saveProjectedRoadWidth", newAssets.filter(_.id == 0L))

      filledTopology
    }

  def getRoadWidthAssetChanges(linearAssets: Seq[PersistedLinearAsset], roadLinks: Seq[RoadLink], changeInfos: Seq[ChangeInfo],
                               fetchModifications: (Seq[Long]) => Seq[AssetLastModification], changedSet: ChangeSet): (Seq[PersistedLinearAsset], ChangeSet) = {

    val mappedLastChanges = changeInfos.filter(_.newId.isDefined).groupBy(_.newId.get).mapValues(c => c.maxBy(_.vvhTimeStamp))
    val mappedLinearAssets = linearAssets.groupBy(_.linkId)
    val mappedRoadLinks = roadLinks.
      filter(road => road.administrativeClass == Municipality || road.administrativeClass == Private).
      filter(road => MTKClassWidth.values.toSeq.contains(road.extractMTKClass(road.attributes))).
      groupBy(_.linkId).mapValues(_.head)

    //Map all existing assets by roadlink and changeinfo
    val changedAssets = mappedLastChanges.map {
      case (linkId, changeInfo) =>
        (mappedRoadLinks.get(linkId), changeInfo, mappedLinearAssets.getOrElse(linkId, Seq()))
    }

    val expiredAssets = changedAssets.flatMap {
      case (_, changeInfo, assets) =>
        assets.filter(asset => asset.modifiedBy.getOrElse(asset.createdBy.getOrElse("")) == "dr1_conversion" ||
          (asset.vvhTimeStamp < changeInfo.vvhTimeStamp && asset.modifiedBy.getOrElse(asset.createdBy.getOrElse("")) == "vvh_mtkclass_default")
        ).map{asset => (asset.id, asset.linkId)}
      case _ =>
        List()
    }.toSet

    val newAssetIds = changedAssets.filter(_._3.isEmpty).map(_._2.newId.get)
    val assetsLastModification = if(newAssetIds.isEmpty) Map[Long, AssetLastModification]() else {
      fetchModifications(newAssetIds.toSeq).groupBy(_.linkId)
    }

    val newAssets = changedAssets.flatMap{
      case (Some(roadLink), changeInfo, assets) if assets.isEmpty =>
        assetsLastModification.get(roadLink.linkId) match {
          case Some(_) =>
            None
          case _ =>
            Some(PersistedLinearAsset(0L, roadLink.linkId, SideCode.BothDirections.value, Some(NumericValue(roadLink.extractMTKClass(roadLink.attributes).width)),
              0, GeometryUtils.geometryLength(roadLink.geometry), Some("vvh_mtkclass_default"), None, None, None, false, LinearAssetTypes.RoadWidthAssetTypeId,
              changeInfo.vvhTimeStamp, None, linkSource = roadLink.linkSource, getVerifiedBy("vvh_mtkclass_default", LinearAssetTypes.RoadWidthAssetTypeId), None))
        }
      case (Some(roadLink), changeInfo, assets) =>
        //if the asset was created by changeInfo and there is a new changeInfo, expire and crete a new asset
        assets.filter(asset => expiredAssets.map(_._2).contains(asset.linkId)).map { asset =>
        PersistedLinearAsset(0L, roadLink.linkId, SideCode.BothDirections.value, Some(NumericValue(roadLink.extractMTKClass(roadLink.attributes).width)),
          asset.startMeasure, asset.endMeasure, asset.createdBy, asset.createdDateTime, Some("vvh_mtkclass_default"), None, false, LinearAssetTypes.RoadWidthAssetTypeId,
          changeInfo.vvhTimeStamp, None, linkSource = roadLink.linkSource, getVerifiedBy("vvh_mtkclass_default", LinearAssetTypes.RoadWidthAssetTypeId), None)}
      case _ =>
        None
    }.toSeq

    (newAssets , changedSet.copy(expiredAssetIds = changedSet.expiredAssetIds ++ expiredAssets.map(_._1).filterNot(_ == 0)))
  }

  override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset]): Unit ={
    if (newLinearAssets.nonEmpty)
      logger.info("Saving projected road Width assets")

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
        val id = (linearAsset.createdBy, linearAsset.createdDateTime) match {
          case (Some(createdBy), Some(createdDateTime)) =>
            dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
              Measures(linearAsset.startMeasure, linearAsset.endMeasure), linearAsset.modifiedBy.getOrElse(LinearAssetTypes.VvhGenerated), linearAsset.vvhTimeStamp,
              getLinkSource(roadLinks.find(_.linkId == linearAsset.linkId)), fromUpdate = true, Some(createdBy), Some(createdDateTime), linearAsset.verifiedBy, linearAsset.verifiedDate)
          case _ =>
            dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
              Measures(linearAsset.startMeasure, linearAsset.endMeasure), linearAsset.createdBy.getOrElse(LinearAssetTypes.VvhGenerated), linearAsset.vvhTimeStamp,
              getLinkSource(roadLinks.find(_.linkId == linearAsset.linkId)))
        }
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

  override protected def updateWithoutTransaction(ids: Seq[Long], value: Value, username: String, measures: Option[Measures] = None, vvhTimeStamp: Option[Long] = None, sideCode: Option[Int] = None): Seq[Long] = {
    if (ids.isEmpty)
      return ids

    ids.flatMap { id =>
      updateValueByExpiration(id, value.asInstanceOf[NumericValue], LinearAssetTypes.numericValuePropertyId, username, measures, vvhTimeStamp, sideCode)
    }
  }

  override protected def createWithoutTransaction(typeId: Int, linkId: Long, value: Value, sideCode: Int, measures: Measures, username: String, vvhTimeStamp: Long, roadLink: Option[RoadLinkLike], fromUpdate: Boolean = false,
                                                  createdByFromUpdate: Option[String] = Some(""),
                                                  createdDateTimeFromUpdate: Option[DateTime] = Some(DateTime.now()), verifiedBy: Option[String]): Long = {
    val id = dao.createLinearAsset(typeId, linkId, expired = false, sideCode, measures, username,
      vvhTimeStamp, getLinkSource(roadLink), fromUpdate, createdByFromUpdate, createdDateTimeFromUpdate, verifiedBy)
    value match {
      case NumericValue(intValue) =>
        dao.insertValue(id, LinearAssetTypes.numericValuePropertyId, intValue)
      case _ => None
    }
    id
  }

  override def updateChangeSet(changeSet: ChangeSet) : Unit = {
    withDynTransaction {
      dao.floatLinearAssets(changeSet.droppedAssetIds)

      if (changeSet.adjustedMValues.nonEmpty)
        logger.info("Saving adjustments for asset/link ids=" + changeSet.adjustedMValues.map(a => "" + a.assetId + "/" + a.linkId).mkString(", "))

      changeSet.adjustedMValues.foreach { adjustment =>
        dao.updateMValues(adjustment.assetId, (adjustment.startMeasure, adjustment.endMeasure))
      }

      changeSet.adjustedSideCodes.foreach { adjustment =>
        dao.updateSideCode(adjustment.assetId, adjustment.sideCode)
      }

      val ids = changeSet.expiredAssetIds.toSeq
      if (ids.nonEmpty)
        logger.info("Expiring ids " + ids.mkString(", "))
      ids.foreach(dao.updateExpiration(_, expired = true, "vvh_mtkclass_default"))
    }
  }

  override def getPersistedAssetsByIds(typeId: Int, ids: Set[Long]): Seq[PersistedLinearAsset] = {
    withDynTransaction {
      dao.fetchLinearAssetsByIds(ids, LinearAssetTypes.getValuePropertyId(typeId))
    }
  }
}
