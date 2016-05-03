package fi.liikennevirasto.digiroad2.linearasset

import fi.liikennevirasto.digiroad2.GeometryUtils.Projection
import fi.liikennevirasto.digiroad2.asset.{TrafficDirection, SideCode}
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller.{SideCodeAdjustment, ChangeSet, MValueAdjustment}
import fi.liikennevirasto.digiroad2.{GeometryUtils}

object NumericalLimitFiller {
  private val AllowedTolerance = 0.5
  private val MaxAllowedError = 0.01

  private def modifiedSort(left: PersistedLinearAsset, right: PersistedLinearAsset) = {
    val leftStamp = left.modifiedDateTime.orElse(left.createdDateTime)
    val rightStamp = right.modifiedDateTime.orElse(right.createdDateTime)
    (leftStamp, rightStamp) match {
      case (Some(l), Some(r)) => l.isAfter(r)
      case (None, Some(r)) => false
      case (Some(l), None) => true
      case (None, None) => true
    }
  }

  private def adjustAsset(asset: PersistedLinearAsset, roadLink: RoadLink): (PersistedLinearAsset, Seq[MValueAdjustment]) = {
    val roadLinkLength = GeometryUtils.geometryLength(roadLink.geometry)
    val adjustedStartMeasure = if (asset.startMeasure < AllowedTolerance && asset.startMeasure > MaxAllowedError) Some(0.0) else None
    val endMeasureDifference: Double = roadLinkLength - asset.endMeasure
    val adjustedEndMeasure = if (endMeasureDifference < AllowedTolerance && endMeasureDifference > MaxAllowedError) Some(roadLinkLength) else None
    val mValueAdjustments = (adjustedStartMeasure, adjustedEndMeasure) match {
      case (None, None) => Nil
      case (s, e)       => Seq(MValueAdjustment(asset.id, asset.linkId, s.getOrElse(asset.startMeasure), e.getOrElse(asset.endMeasure)))
    }
    val adjustedAsset = asset.copy(
      startMeasure = adjustedStartMeasure.getOrElse(asset.startMeasure),
      endMeasure = adjustedEndMeasure.getOrElse(asset.endMeasure))
    (adjustedAsset, mValueAdjustments)
  }

  private def extendHead(mStart: Double, candidates: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Double, ChangeSet) = {
    val maybeAsset = candidates.find(a => Math.abs(a.endMeasure - mStart) < MaxAllowedError)
    maybeAsset match {
      case Some(extension) =>
        extendHead(extension.startMeasure, candidates.diff(Seq(extension)),
          changeSet.copy(droppedAssetIds = changeSet.droppedAssetIds ++ Set(extension.id)))
      case None => (mStart, changeSet)

    }
  }
  private def extendTail(mEnd: Double, candidates: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Double, ChangeSet) = {
    val maybeAsset = candidates.find(a => Math.abs(a.startMeasure - mEnd) < MaxAllowedError)
    maybeAsset match {
      case Some(extension) =>
        extendTail(extension.endMeasure, candidates.diff(Seq(extension)),
          changeSet.copy(droppedAssetIds = changeSet.droppedAssetIds ++ Set(extension.id)))
      case None => (mEnd, changeSet)
    }
  }

  /**
    * Try to find assets that start/end adjacent to the keeper asset and merge them to the keeper
    * @param asset keeper
    * @param candidates merging candidates
    * @param changeSet current changeset
    * @return updated keeper asset and updated changeset
    */
  private def extend(asset: PersistedLinearAsset, candidates: Seq[PersistedLinearAsset], changeSet: ChangeSet): (PersistedLinearAsset, ChangeSet) = {
    if (candidates.isEmpty) {
      (asset, changeSet)
    } else {
      val (mStart, changeSetH) = extendHead(asset.startMeasure, candidates.filter(a =>
        (a.endMeasure < asset.startMeasure + MaxAllowedError) && a.value.equals(asset.value)), changeSet)
      val (mEnd, changeSetHT) = extendTail(asset.endMeasure, candidates.filter(a =>
        (a.startMeasure > asset.endMeasure - MaxAllowedError) && a.value.equals(asset.value)), changeSetH)
      val mValueAdjustments = (mStart == asset.startMeasure, mEnd == asset.endMeasure) match {
        case (true, true) => Nil
        case (s, e) => Seq(MValueAdjustment(asset.id, asset.linkId, mStart, mEnd))
      }
      val adjustedAsset = asset.copy(
        startMeasure = mStart,
        endMeasure = mEnd)
      (adjustedAsset, changeSetHT.copy(adjustedMValues = changeSet.adjustedMValues ++ mValueAdjustments))
    }
  }

  /**
    * Adjust two way segments and combine them if possible using tail recursion functions above
    * @param roadLink
    * @param assets
    * @param changeSet
    * @return
    */
  def adjustTwoWaySegments(roadLink: RoadLink, assets: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Seq[PersistedLinearAsset], ChangeSet) = {
    val twoWaySegments = assets.filter(_.sideCode == 1).sortWith(modifiedSort)
    if (twoWaySegments.length == 1 && assets.forall(_.sideCode == 1)) {
      val asset = twoWaySegments.head
      val (adjustedAsset, mValueAdjustments) = adjustAsset(asset, roadLink)
      (Seq(adjustedAsset), changeSet.copy(adjustedMValues = changeSet.adjustedMValues ++ mValueAdjustments))
    } else {
      if (twoWaySegments.length > 1) {
        val asset = twoWaySegments.head
        val rest = twoWaySegments.tail
        val (updatedAsset, newChangeSet) = extend(asset, rest, changeSet)
        val (adjustedAsset, mValueAdjustments) = adjustAsset(updatedAsset, roadLink)
        (rest.filterNot(p => newChangeSet.droppedAssetIds.contains(p.id)) ++ Seq(adjustedAsset),
          newChangeSet.copy(adjustedMValues = newChangeSet.adjustedMValues ++ mValueAdjustments))
      } else {
        (assets, changeSet)
      }
    }
  }

  private def dropSegmentsOutsideGeometry(roadLink: RoadLink, assets: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Seq[PersistedLinearAsset], ChangeSet) = {
    val (segmentsWithinGeometry, segmentsOutsideGeometry) = assets.partition(_.startMeasure < roadLink.length)
    val droppedAssetIds = segmentsOutsideGeometry.map(_.id).toSet
    (segmentsWithinGeometry, changeSet.copy(droppedAssetIds = changeSet.droppedAssetIds ++ droppedAssetIds))
  }

  private def capSegmentsThatOverflowGeometry(roadLink: RoadLink, assets: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Seq[PersistedLinearAsset], ChangeSet) = {
    val (validSegments, overflowingSegments) = assets.partition(_.endMeasure <= roadLink.length)
    val cappedSegments = overflowingSegments.map { x => x.copy(endMeasure = roadLink.length)}
    val mValueAdjustments = cappedSegments.map { x => MValueAdjustment(x.id, x.linkId, x.startMeasure, x.endMeasure) }
    (validSegments ++ cappedSegments, changeSet.copy(adjustedMValues = changeSet.adjustedMValues ++ mValueAdjustments))
  }

  private def adjustSegmentSideCodes(roadLink: RoadLink, segments: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Seq[PersistedLinearAsset], ChangeSet) = {
    val oneWayTrafficDirection =
      (roadLink.trafficDirection == TrafficDirection.TowardsDigitizing) ||
      (roadLink.trafficDirection == TrafficDirection.AgainstDigitizing)

    if (!oneWayTrafficDirection) {
      (segments, changeSet)
    } else {
      val (twoSided, oneSided) = segments.partition { s => s.sideCode == SideCode.BothDirections.value }
      val adjusted = oneSided.map { s => (s.copy(sideCode = SideCode.BothDirections.value), SideCodeAdjustment(s.id, SideCode.BothDirections)) }
      (twoSided ++ adjusted.map(_._1), changeSet.copy(adjustedSideCodes = changeSet.adjustedSideCodes ++ adjusted.map(_._2)))
    }
  }

  private def generateTwoSidedNonExistingLinearAssets(typeId: Int)(roadLink: RoadLink, segments: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Seq[PersistedLinearAsset], ChangeSet) = {
    val lrmPositions: Seq[(Double, Double)] = segments.map { x => (x.startMeasure, x.endMeasure) }
    val remainders = lrmPositions.foldLeft(Seq((0.0, roadLink.length)))(GeometryUtils.subtractIntervalFromIntervals).filter { case (start, end) => math.abs(end - start) > 0.5}
    val generated = remainders.map { segment =>
      PersistedLinearAsset(0L, roadLink.linkId, 1, None, segment._1, segment._2, None, None, None, None, false, typeId, 0, None)
    }
    (segments ++ generated, changeSet)
  }

  private def generateOneSidedNonExistingLinearAssets(sideCode: SideCode, typeId: Int)(roadLink: RoadLink, segments: Seq[PersistedLinearAsset], changeSet: ChangeSet): (Seq[PersistedLinearAsset], ChangeSet) = {
    val generated = if (roadLink.trafficDirection == TrafficDirection.BothDirections) {
      val lrmPositions: Seq[(Double, Double)] = segments
        .filter { s => s.sideCode == sideCode.value || s.sideCode == SideCode.BothDirections.value }
        .map { x => (x.startMeasure, x.endMeasure) }
      val remainders = lrmPositions.foldLeft(Seq((0.0, roadLink.length)))(GeometryUtils.subtractIntervalFromIntervals).filter { case (start, end) => math.abs(end - start) > 0.5 }
      remainders.map { segment =>
        PersistedLinearAsset(0L, roadLink.linkId, sideCode.value, None, segment._1, segment._2, None, None, None, None, false, typeId, 0, None)
      }
    } else {
      Nil
    }
    (segments ++ generated, changeSet)
  }

  private def toLinearAsset(dbAssets: Seq[PersistedLinearAsset], roadLink: RoadLink): Seq[PieceWiseLinearAsset] = {
    dbAssets.map { dbAsset =>
      val points = GeometryUtils.truncateGeometry(roadLink.geometry, dbAsset.startMeasure, dbAsset.endMeasure)
      val endPoints = GeometryUtils.geometryEndpoints(points)
      PieceWiseLinearAsset(
        dbAsset.id, dbAsset.linkId, SideCode(dbAsset.sideCode), dbAsset.value, points, dbAsset.expired,
        dbAsset.startMeasure, dbAsset.endMeasure,
        Set(endPoints._1, endPoints._2), dbAsset.modifiedBy, dbAsset.modifiedDateTime,
        dbAsset.createdBy, dbAsset.createdDateTime, dbAsset.typeId, roadLink.trafficDirection, 0, None)
    }
  }

  def fillTopology(topology: Seq[RoadLink], linearAssets: Map[Long, Seq[PersistedLinearAsset]], typeId: Int): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    val fillOperations: Seq[(RoadLink, Seq[PersistedLinearAsset], ChangeSet) => (Seq[PersistedLinearAsset], ChangeSet)] = Seq(
      dropSegmentsOutsideGeometry,
      capSegmentsThatOverflowGeometry,
      adjustTwoWaySegments,
      adjustSegmentSideCodes,
      generateTwoSidedNonExistingLinearAssets(typeId),
      generateOneSidedNonExistingLinearAssets(SideCode.TowardsDigitizing, typeId),
      generateOneSidedNonExistingLinearAssets(SideCode.AgainstDigitizing, typeId)
    )

    topology.foldLeft(Seq.empty[PieceWiseLinearAsset], ChangeSet(Set.empty, Nil, Nil, Set.empty)) { case (acc, roadLink) =>
      val (existingAssets, changeSet) = acc
      val assetsOnRoadLink = linearAssets.getOrElse(roadLink.linkId, Nil)

      val (adjustedAssets, assetAdjustments) = fillOperations.foldLeft(assetsOnRoadLink, changeSet) { case ((currentSegments, currentAdjustments), operation) =>
        operation(roadLink, currentSegments, currentAdjustments)
      }

      (existingAssets ++ toLinearAsset(adjustedAssets, roadLink), assetAdjustments)
    }
  }

  private def calculateNewMValuesAndSideCode(asset: PersistedLinearAsset, projection: Projection, roadLinkLength: Double) = {
    val oldLength = projection.oldEnd - projection.oldStart
    val newLength = projection.newEnd - projection.newStart

    // Test if the direction has changed -> side code will be affected, too
    if (GeometryUtils.isDirectionChangeProjection(projection)) {
      val newSideCode = SideCode.apply(asset.sideCode) match {
        case (SideCode.AgainstDigitizing) => SideCode.TowardsDigitizing.value
        case (SideCode.TowardsDigitizing) => SideCode.AgainstDigitizing.value
        case _ => asset.sideCode
      }
      val newStart = projection.newStart - (asset.endMeasure - projection.oldStart) * Math.abs(newLength/oldLength)
      val newEnd = projection.newEnd - (asset.startMeasure - projection.oldEnd) * Math.abs(newLength/oldLength)
      (Math.min(roadLinkLength, Math.max(0.0, newStart)), Math.max(0.0, Math.min(roadLinkLength, newEnd)), newSideCode)
    } else {
      val newStart = projection.newStart + (asset.startMeasure - projection.oldStart) * Math.abs(newLength/oldLength)
      val newEnd = projection.newEnd + (asset.endMeasure - projection.oldEnd) * Math.abs(newLength/oldLength)
      (Math.min(roadLinkLength, Math.max(0.0, newStart)), Math.max(0.0, Math.min(roadLinkLength, newEnd)), asset.sideCode)
    }
  }

  def projectLinearAsset(asset: PersistedLinearAsset, to: RoadLink, projection: Projection) = {
    val newLinkId = to.linkId
    val assetId = asset.linkId match {
      case to.linkId => asset.id
      case _ => 0
    }
    val (newStart, newEnd, newSideCode) = calculateNewMValuesAndSideCode(asset, projection, to.length)
    val geometry = GeometryUtils.truncateGeometry(
      Seq(GeometryUtils.calculatePointFromLinearReference(to.geometry, newStart).getOrElse(to.geometry.head),
        GeometryUtils.calculatePointFromLinearReference(to.geometry, newEnd).getOrElse(to.geometry.last)),
      0, to.length)

    PersistedLinearAsset(id = assetId, linkId = newLinkId, sideCode = newSideCode,
      value = asset.value, startMeasure = newStart, endMeasure = newEnd,
      createdBy = asset.createdBy, createdDateTime = asset.createdDateTime, modifiedBy = asset.modifiedBy,
      modifiedDateTime = asset.modifiedDateTime, expired = false, typeId = asset.typeId,
      vvhTimeStamp = projection.vvhTimeStamp, geomModifiedDate = None
    )
  }
}
