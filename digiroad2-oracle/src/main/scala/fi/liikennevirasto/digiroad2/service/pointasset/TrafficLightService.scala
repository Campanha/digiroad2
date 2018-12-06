package fi.liikennevirasto.digiroad2.service.pointasset

import fi.liikennevirasto.digiroad2.PointAssetFiller.AssetAdjustment
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource}
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.pointasset.{OracleTrafficLightDao, TrafficLight}
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.User
import org.joda.time.DateTime

case class IncomingTrafficLight(lon: Double, lat: Double, linkId: Long) extends IncomingPointAsset
case class IncomingTrafficLightAsset(linkId: Long, mValue: Long) extends IncomePointAsset

class TrafficLightService(val roadLinkService: RoadLinkService) extends PointAssetOperations {
  type IncomingAsset = IncomingTrafficLight
  type PersistedAsset = TrafficLight

  override def typeId: Int = 280

  override def setAssetPosition(asset: IncomingTrafficLight, geometry: Seq[Point], mValue: Double): IncomingTrafficLight = {
    GeometryUtils.calculatePointFromLinearReference(geometry, mValue) match {
      case Some(point) =>
        asset.copy(lon = point.x, lat = point.y)
      case _ =>
        asset
    }
  }

  override def update(id: Long, updatedAsset: IncomingTrafficLight, geometry: Seq[Point], municipality: Int, username: String, linkSource: LinkGeomSource): Long = {
    withDynTransaction {
      updateWithoutTransaction(id, updatedAsset, geometry, municipality, username, linkSource, None, None)
    }
  }

  def updateWithoutTransaction(id: Long, updatedAsset: IncomingTrafficLight, geometry: Seq[Point], municipality: Int, username: String, linkSource: LinkGeomSource, mValue : Option[Double], vvhTimeStamp: Option[Long]): Long = {
    val value = mValue.getOrElse(GeometryUtils.calculateLinearReferenceFromPoint(Point(updatedAsset.lon, updatedAsset.lat), geometry))
    getPersistedAssetsByIdsWithoutTransaction(Set(id)).headOption.getOrElse(throw new NoSuchElementException("Asset not found")) match {
      case old if  old.lat != updatedAsset.lat || old.lon != updatedAsset.lon =>
        expireWithoutTransaction(id)
        OracleTrafficLightDao.create(setAssetPosition(updatedAsset, geometry, value), value, username, municipality, vvhTimeStamp.getOrElse(VVHClient.createVVHTimeStamp()), linkSource, old.createdBy, old.createdAt)
      case _ =>
        OracleTrafficLightDao.update(id, setAssetPosition(updatedAsset, geometry, value), value, username, municipality, Some(vvhTimeStamp.getOrElse(VVHClient.createVVHTimeStamp())), linkSource)
    }
  }

  override def setFloating(persistedAsset: TrafficLight, floating: Boolean) = {
    persistedAsset.copy(floating = floating)
  }

  override def fetchPointAssets(queryFilter: (String) => String, roadLinks: Seq[RoadLinkLike]): Seq[TrafficLight] = {
    OracleTrafficLightDao.fetchByFilter(queryFilter)
  }

  override def create(asset: IncomingTrafficLight, username: String, roadLink: RoadLink): Long = {
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(asset.lon, asset.lat), roadLink.geometry)
    withDynTransaction {
      OracleTrafficLightDao.create(setAssetPosition(asset, roadLink.geometry, mValue), mValue, username, roadLink.municipalityCode, VVHClient.createVVHTimeStamp(), roadLink.linkSource)
    }
  }

  override def getByBoundingBox(user: User, bounds: BoundingRectangle) : Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(bounds)
    super.getByBoundingBox(user, bounds, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation))
  }

  private def createOperation(asset: PersistedAsset, adjustment: AssetAdjustment): PersistedAsset = {
    createPersistedAsset(asset, adjustment)
  }

  private def adjustmentOperation(persistedAsset: PersistedAsset, adjustment: AssetAdjustment, roadLink: RoadLink): Long = {
    val updated = IncomingTrafficLight(adjustment.lon, adjustment.lat, adjustment.linkId)
    updateWithoutTransaction(adjustment.assetId, updated, roadLink.geometry, persistedAsset.municipalityCode, "vvh_generated",
                             persistedAsset.linkSource, Some(adjustment.mValue), Some(adjustment.vvhTimeStamp))
  }

  override def getByMunicipality(municipalityCode: Int): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipalityCode)
    val mapRoadLinks = roadLinks.map(l => l.linkId -> l).toMap
    getByMunicipality(municipalityCode, mapRoadLinks, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation))
  }

  private def createPersistedAsset[T](persistedStop: PersistedAsset, asset: AssetAdjustment) = {

    new PersistedAsset(asset.assetId, asset.linkId, asset.lon, asset.lat,
      asset.mValue, asset.floating, persistedStop.vvhTimeStamp, persistedStop.municipalityCode, persistedStop.createdBy,
      persistedStop.createdAt, persistedStop.modifiedBy, persistedStop.modifiedAt, persistedStop.linkSource)
  }

  override def toIncomingAsset(asset: IncomePointAsset, link: RoadLink) : Option[IncomingTrafficLight] = {
    GeometryUtils.calculatePointFromLinearReference(link.geometry, asset.mValue).map {
      point =>  IncomingTrafficLight(point.x, point.y, link.linkId)
    }
  }

  override def getChanged(sinceDate: DateTime, untilDate: DateTime): Seq[ChangedPointAsset] = { throw new UnsupportedOperationException("Not Supported Method") }

  override def fetchPointAssetsWithExpired(queryFilter: String => String, roadLinks: Seq[RoadLinkLike]): Seq[TrafficLight] =  { throw new UnsupportedOperationException("Not Supported Method") }
}
