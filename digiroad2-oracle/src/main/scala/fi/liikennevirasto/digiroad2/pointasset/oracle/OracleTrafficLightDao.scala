package fi.liikennevirasto.digiroad2.pointasset.oracle

import fi.liikennevirasto.digiroad2.{Point, PersistedPointAsset}
import fi.liikennevirasto.digiroad2.asset.oracle.Queries._
import fi.liikennevirasto.digiroad2.asset.oracle.Sequences
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery}
import slick.jdbc.StaticQuery.interpolation

case class TrafficLight(id: Long, mmlId: Long,
                              lon: Double, lat: Double,
                              mValue: Double, floating: Boolean,
                              municipalityCode: Int,
                              createdBy: Option[String] = None,
                              createdAt: Option[DateTime] = None,
                              modifiedBy: Option[String] = None,
                              modifiedAt: Option[DateTime] = None) extends PersistedPointAsset

case class TrafficLightToBePersisted(mmlId: Long, lon: Double, lat: Double, mValue: Double, municipalityCode: Int, createdBy: String)

object OracleTrafficLightDao {
  def fetchByFilter(queryFilter: String => String): Seq[TrafficLight] = {

    val query =
      """
        select a.id, pos.mml_id, a.geometry, pos.start_measure, a.floating, a.municipality_code, a.created_by, a.created_date, a.modified_by, a.modified_date
        from asset a
        join asset_link al on a.id = al.asset_id
        join lrm_position pos on al.position_id = pos.id
      """
    val queryWithFilter = queryFilter(query) + " and (a.valid_to > sysdate or a.valid_to is null)"
    StaticQuery.queryNA[TrafficLight](queryWithFilter).iterator.toSeq
  }

  implicit val getPointAsset = new GetResult[TrafficLight] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val mmlId = r.nextLong()
      val point = r.nextBytesOption().map(bytesToPoint).get
      val mValue = r.nextDouble()
      val floating = r.nextBoolean()
      val municipalityCode = r.nextInt()
      val createdBy = r.nextStringOption()
      val createdDateTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val modifiedBy = r.nextStringOption()
      val modifiedDateTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))

      TrafficLight(id, mmlId, point.x, point.y, mValue, floating, municipalityCode, createdBy, createdDateTime, modifiedBy, modifiedDateTime)
    }
  }

  def create(trafficLight: TrafficLightToBePersisted, username: String): Long = {
    val id = Sequences.nextPrimaryKeySeqValue
    val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
    sqlu"""
      insert all
        into asset(id, asset_type_id, created_by, created_date, municipality_code)
        values ($id, 280, $username, sysdate, ${trafficLight.municipalityCode})
        into lrm_position(id, start_measure, mml_id)
        values ($lrmPositionId, ${trafficLight.mValue}, ${trafficLight.mmlId})

        into asset_link(asset_id, position_id)
        values ($id, $lrmPositionId)
      select * from dual
    """.execute
    updateAssetGeometry(id, Point(trafficLight.lon, trafficLight.lat))

    id
  }

}