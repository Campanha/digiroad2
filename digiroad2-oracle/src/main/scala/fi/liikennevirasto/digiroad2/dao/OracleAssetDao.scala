package fi.liikennevirasto.digiroad2.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.TrafficSigns
import fi.liikennevirasto.digiroad2.oracle.MassQuery
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class AssetLink(id: Long, linkId: Long, startMeasure: Double, endMeasure: Double)

class OracleAssetDao {

  implicit val getAssetLink = new GetResult[AssetLink] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val linkId = r.nextLong()
      val startMeasure = r.nextDouble()
      val endMeasure = r.nextDouble()

      AssetLink(id, linkId, startMeasure, endMeasure)
    }
  }

  def getLastExecutionDate(typeId: Int, createdBy: String): Option[DateTime] = {

    sql""" select MAX( case when a.modified_date is null then MAX(a.created_date) else MAX(a.modified_date) end ) as lastExecution
           from asset a
           where a.created_by = $createdBy and ( a.modified_by = $createdBy or a.modified_by is null) and a.asset_type_id = $typeId
           group by a.modified_date, a.created_date""".as[DateTime].firstOption

  }

  def getGeometryType(typeId: Int): String = {
    val geometryType = sql""" select GEOMETRY_TYPE from asset_type where id = $typeId""".as[String].firstOption.get
    geometryType
  }

  /**
    * When invoked will expire assets by Id.
    * It is required that the invoker takes care of the transaction.
    *
    * @param id Represets the id of the asset
    */
  def expireAssetsById (id: Long): Unit = {
    sqlu"update asset set valid_to = sysdate - 1/86400 where id = $id".execute
  }

  /**
    * When invoked will expire assets by type id and link ids.
    * It is required that the invoker takes care of the transaction.
    *
    * @param typeId Represets the id of the asset
    * @param linkIds Represets the link id of the road
    */
  def expireAssetByTypeAndLinkId(typeId: Long, linkIds: Seq[Long]): Unit = {
    MassQuery.withIds(linkIds.toSet) { idTableName =>
      sqlu"""
         update asset set valid_to = sysdate - 1/86400 where id in (
          select a.id
          from asset a
          join asset_link al on al.asset_id = a.id
          join lrm_position lrm on lrm.id = al.position_id
          join  #$idTableName i on i.id = lrm.link_id
          where a.asset_type_id = $typeId AND (a.valid_to IS NULL OR a.valid_to > SYSDATE ) AND a.floating = 0
         )
      """.execute
    }
  }

  /**
    * Returns the municipality code of a Asset by it's Id
    *
    * @param assetId The Id of the Asset
    * @return Type: Int - The Municipality Code
    */
  def getAssetMunicipalityCodeById(assetId: Int): Int = {
    val municipalityCode = sql"""Select municipality_code From asset Where id= $assetId""".as[Int].firstOption.get
    municipalityCode
  }

  def getAssetTypeId(ids: Seq[Long]): Seq[(Long, Int)] = {
    sql"""select ID, ASSET_TYPE_ID from ASSET where ID in (#${ids.mkString(",")})""".as[(Long, Int)].list
  }

  def getVerifiableAssetTypes: Seq[String] = {
    val assetTypes = sql"""select name from asset_type where verifiable = 1""".as[(String)].list
    assetTypes
  }

  def getAssetIdByLinks(typeId: Long, linkIds: Seq[Long]) = {
    MassQuery.withIds(linkIds.toSet) { idTableName =>
      sql"""select A.ID from ASSET A
         join ASSET_LINK AL on AL.ASSET_ID = A.ID
         join LRM_POSITION lrm on lrm.ID = AL.POSITION_ID
         join  #$idTableName i on i.id = lrm.link_id
         where A.ASSET_TYPE_ID = $typeId AND (A.valid_to IS NULL OR A.valid_to > SYSDATE ) """.as[Long].list
    }
  }

  def getAssetsByTypesAndLinkId(assetTypeId: Set[Int], linkIds: Seq[Long]): Seq[AssetLink]  = {
    MassQuery.withIds(linkIds.toSet) { idTableName =>
      sql"""select a.id, lp.link_id, lp.start_measure, lp.end_measure
            from asset a
            join asset_link al on al.asset_id = a.id
            join lrm_position lp on lp.id = al.position_id
            join #$idTableName i on i.id = lp.link_id
            where a.asset_type_id in (#${assetTypeId.mkString(",")}) and (a.valid_to is null or a.valid_to > sysdate)""".as[AssetLink].list
    }
  }

  def updateAssetsWithGeometry(asset: AssetLink, startPoint: Point, endPoint: Point) = {
    val assetLength = asset.endMeasure - asset.startMeasure
    sqlu"""UPDATE asset
          SET geometry = MDSYS.SDO_GEOMETRY(4002,
                                            3067,
                                            NULL,
                                            MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),
                                            MDSYS.SDO_ORDINATE_ARRAY(${startPoint.x},${startPoint.y},0,0.0,${endPoint.x},${endPoint.y},0,${assetLength}))
          WHERE id = ${asset.id}
      """.execute
  }

  def expireWithoutTransaction(id: Long, username: String) = {
    Queries.updateAssetModified(id, username).first
    sqlu"update asset set valid_to = sysdate where id = $id".first
  }
}
