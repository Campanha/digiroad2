package fi.liikennevirasto.digiroad2.service.pointasset

import fi.liikennevirasto.digiroad2.{GeometryUtils, PersistedPointAsset, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.dao.pointasset.OraclePointMassLimitationDao
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, Value}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.User
import org.joda.time.DateTime

case class MassLimitationPointAsset()

case class WeightGroupLimitation(id: Long,
                                typeId: Int,
                                linkId: Long,
                                lon: Double, lat: Double,
                                mValue: Double, floating: Boolean,
                                vvhTimeStamp: Long,
                                municipalityCode: Int,
                                createdBy: Option[String] = None,
                                createdAt: Option[DateTime] = None,
                                modifiedBy: Option[String] = None,
                                modifiedAt: Option[DateTime] = None,
                                linkSource: LinkGeomSource,
                                limit: Double) extends PersistedPointAsset


class PointMassLimitationService(roadLinkService: RoadLinkService, dao: OraclePointMassLimitationDao) {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  val pointMassLimitationTypes = Seq(TrTrailerTruckWeightLimit.typeId,
    TrAxleWeightLimit.typeId, TrWeightLimit.typeId, TrBogieWeightLimit.typeId)


  def getByBoundingBox(user: User, bounds: BoundingRectangle) :Seq[Seq[PersistedPointAsset]] = {
    getByRoadLinks(pointMassLimitationTypes, bounds)
  }

  def getByRoadLinks(typeIds: Seq[Int], bounds: BoundingRectangle): Seq[Seq[PersistedPointAsset]] = {
    withDynTransaction {
      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(bounds, "a.geometry")
      val filter = s"where $boundingBoxFilter"
      val assets = dao.fetchByBoundingBox(typeIds, withFilter(filter))

      assets.foldLeft(Seq.empty[Seq[PersistedPointAsset]]) {
        (prev, asset) =>
          if(prev.exists( x => x.exists(c => c.id == asset.id))) {
            prev
          }
          else
         {
           prev ++ Seq(assets.filter( assetx => GeometryUtils.geometryLength(Seq(Point(asset.lon, asset.lat), Point(assetx.lon, assetx.lat))) < 1 ))
         }
      }

    }
  }

  protected def withFilter(filter: String)(query: String): String = {
    query + " " + filter
  }

}
