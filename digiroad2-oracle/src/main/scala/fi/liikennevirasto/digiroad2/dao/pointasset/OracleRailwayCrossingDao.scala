package fi.liikennevirasto.digiroad2.dao.pointasset

import fi.liikennevirasto.digiroad2.dao.Queries._
import fi.liikennevirasto.digiroad2.{PersistedPointAsset, Point}
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.service.pointasset.IncomingRailwayCrossing
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery}
import com.github.tototoshi.slick.MySQLJodaSupport._

case class RailwayCrossing(id: Long, linkId: Long,
                           lon: Double, lat: Double,
                           mValue: Double, floating: Boolean,
                           vvhTimeStamp: Long,
                           municipalityCode: Int,
                           safetyEquipment: Int,
                           name: Option[String],
                           code: String,
                           createdBy: Option[String] = None,
                           createdAt: Option[DateTime] = None,
                           modifiedBy: Option[String] = None,
                           modifiedAt: Option[DateTime] = None,
                           linkSource: LinkGeomSource) extends PersistedPointAsset

object OracleRailwayCrossingDao {
  // This works as long as there are only two properties of different types for railway crossings
  def fetchByFilter(queryFilter: String => String): Seq[RailwayCrossing] = {
    val query =
      s"""
        select a.id, pos.link_id, a.geometry, pos.start_measure, a.floating, pos.adjusted_timestamp, a.municipality_code, ev.value,
        tpv.value_fi as name, tpvCode.value_fi as code, a.created_by, a.created_date, a.modified_by, a.modified_date, pos.link_source
        from asset a
        join asset_link al on a.id = al.asset_id
        join lrm_position pos on al.position_id = pos.id
        left join single_choice_value scv on scv.asset_id = a.id
        left join enumerated_value ev on (ev.property_id = $getSafetyEquipmentPropertyId AND scv.enumerated_value_id = ev.id)
        left join text_property_value tpv on (tpv.property_id = $getNamePropertyId AND tpv.asset_id = a.id)
        left join text_property_value tpvCode on (tpvCode.property_id = $getCodePropertyId AND tpvCode.asset_id = a.id)
      """
    val queryWithFilter = queryFilter(query) + " and (a.valid_to > sysdate or a.valid_to is null) "
    StaticQuery.queryNA[RailwayCrossing](queryWithFilter).iterator.toSeq
  }

  implicit val getPointAsset = new GetResult[RailwayCrossing] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val linkId = r.nextLong()
      val point = r.nextBytesOption().map(bytesToPoint).get
      val mValue = r.nextDouble()
      val floating = r.nextBoolean()
      val vvhTimeStamp = r.nextLong()
      val municipalityCode = r.nextInt()
      val safetyEquipment = r.nextInt()
      val name = r.nextStringOption()
      val code = r.nextString()
      val createdBy = r.nextStringOption()
      val createdDateTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val modifiedBy = r.nextStringOption()
      val modifiedDateTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val linkSource = r.nextInt()

      RailwayCrossing(id, linkId, point.x, point.y, mValue, floating, vvhTimeStamp, municipalityCode, safetyEquipment, name, code, createdBy, createdDateTime, modifiedBy, modifiedDateTime, linkSource = LinkGeomSource(linkSource))
    }
  }

  def create(asset: IncomingRailwayCrossing, mValue: Double, municipality: Int, username: String, adjustedTimestamp: Long, linkSource: LinkGeomSource): Long = {
    val id = Sequences.nextPrimaryKeySeqValue
    val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
    sqlu"""
      insert all
        into asset(id, asset_type_id, created_by, created_date, municipality_code)
        values ($id, 230, $username, sysdate, $municipality)

        into lrm_position(id, start_measure, link_id, adjusted_timestamp, link_source)
        values ($lrmPositionId, $mValue, ${asset.linkId}, $adjustedTimestamp, ${linkSource.value})

        into asset_link(asset_id, position_id)
        values ($id, $lrmPositionId)

      select * from dual
    """.execute
    updateAssetGeometry(id, Point(asset.lon, asset.lat))
    insertSingleChoiceProperty(id, getSafetyEquipmentPropertyId, asset.safetyEquipment).execute
    asset.name.foreach(insertTextProperty(id, getNamePropertyId, _).execute)
    insertTextProperty(id, getCodePropertyId, asset.code).execute
    id
  }
  def create(asset: IncomingRailwayCrossing, mValue: Double, municipality: Int, username: String, adjustedTimestamp: Long, linkSource: LinkGeomSource, createdByFromUpdate: Option[String] = Some(""), createdDateTimeFromUpdate: Option[DateTime]): Long = {
    val id = Sequences.nextPrimaryKeySeqValue
    val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
    sqlu"""
      insert all
        into asset(id, asset_type_id, created_by, created_date, municipality_code, modified_by, modified_date)
        values ($id, 230, $createdByFromUpdate, $createdDateTimeFromUpdate, $municipality, $username, sysdate)

        into lrm_position(id, start_measure, link_id, adjusted_timestamp, link_source, modified_date)
        values ($lrmPositionId, $mValue, ${asset.linkId}, $adjustedTimestamp, ${linkSource.value}, sysdate)

        into asset_link(asset_id, position_id)
        values ($id, $lrmPositionId)

      select * from dual
    """.execute
    updateAssetGeometry(id, Point(asset.lon, asset.lat))
    insertSingleChoiceProperty(id, getSafetyEquipmentPropertyId, asset.safetyEquipment).execute
    asset.name.foreach(insertTextProperty(id, getNamePropertyId, _).execute)
    id
  }

  def update(id: Long, railwayCrossing: IncomingRailwayCrossing, mValue: Double, municipality: Int, username: String, adjustedTimeStampOption: Option[Long] = None, linkSource: LinkGeomSource) = {
    sqlu""" update asset set municipality_code = $municipality where id = $id """.execute
    updateAssetModified(id, username).execute
    updateAssetGeometry(id, Point(railwayCrossing.lon, railwayCrossing.lat))
    updateSingleChoiceProperty(id, getSafetyEquipmentPropertyId, railwayCrossing.safetyEquipment).execute
    deleteTextProperty(id, getNamePropertyId).execute
    deleteTextProperty(id, getCodePropertyId).execute
    railwayCrossing.name.foreach(insertTextProperty(id, getNamePropertyId, _).execute)
    insertTextProperty(id, getCodePropertyId, railwayCrossing.code).execute

    adjustedTimeStampOption match {
      case Some(adjustedTimeStamp) =>
        sqlu"""
          update lrm_position
           set
           start_measure = $mValue,
           link_id = ${railwayCrossing.linkId},
           adjusted_timestamp = ${adjustedTimeStamp},
           link_source = ${linkSource.value}
           where id = (select position_id from asset_link where asset_id = $id)
        """.execute
      case _ =>
        sqlu"""
          update lrm_position
           set
           start_measure = $mValue,
           link_id = ${railwayCrossing.linkId},
           link_source = ${linkSource.value}
           where id = (select position_id from asset_link where asset_id = $id)
        """.execute
    }
    id
  }

  private def getSafetyEquipmentPropertyId: Long = {
    StaticQuery.query[String, Long](Queries.propertyIdByPublicId).apply("turvavarustus").first
  }

  private def getNamePropertyId: Long = {
    StaticQuery.query[String, Long](Queries.propertyIdByPublicId).apply("rautatien_tasoristeyksen_nimi").first
  }

  private def getCodePropertyId: Long = {
    StaticQuery.query[String, Long](Queries.propertyIdByPublicId).apply("tasoristeystunnus").first
  }
}



