package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.ConversionDatabase._

import scala.slick.driver.JdbcDriver.backend.Database
import scala.slick.jdbc.StaticQuery.interpolation
import scala.slick.driver.JdbcDriver.backend.Database.dynamicSession

object PointAssetService {
  def getServicePoints(): Seq[Map[String, Any]] = {
    Database.forDataSource(dataSource).withDynTransaction {
      val query = sql"""
         select p.palv_tyyppi, p.palv_lisatieto, p.palv_rautatieaseman_tyyppi, p.palv_paikkojen_lukumaara, p.palv_lepoalue_tyyppi, to_2d(p.shape), p.dr1_oid, p.nimi_fi
           from palvelupisteet p
        """
      query.as[(Int, Option[String], Option[Int], Option[Int], Option[Int], Seq[Point], Long, Option[String])].iterator().map {
        case (serviceType, extraInfo, railwayStationType, parkingPlaceCount, restAreaType, geometry, id, name) =>
          Map("id" -> id, "geometry" -> geometry.head, "serviceType" -> serviceType, "extraInfo" -> extraInfo, "railwayStationType" -> railwayStationType, "parkingPlaceCount" -> parkingPlaceCount, "restAreaType" -> restAreaType, "name" -> name)
      }.toSeq
    }
  }

  def getByMunicipality(typeId: Int, municipalityNumber: Int): Seq[Map[String, Any]] = {
    Database.forDataSource(dataSource).withDynTransaction {
      val query = sql"""
         select s.segm_id, s.tielinkki_id, to_2d(sdo_lrs.dynamic_segment(t.shape, s.alkum, s.loppum)), s.puoli
           from segments s
           join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
           where t.kunta_nro = $municipalityNumber and s.tyyppi = $typeId
        """
      query.as[(Long, Long, Seq[Point], Int)].iterator().map {
        case (id, roadLinkId, geometry, sideCode) => Map("id" -> id, "point" -> geometry.head, "sideCode" -> sideCode)
      }.toSeq
    }
  }

  def getDirectionalTrafficSignsByMunicipality(municipalityNumber: Int): Seq[Map[String, Any]] = {
    Database.forDataSource(dataSource).withDynTransaction {
      val query = sql"""
         select s.segm_id, s.tielinkki_id, to_2d(sdo_lrs.dynamic_segment(t.shape, s.alkum, s.loppum)), s.puoli, s.opas_teksti
           from segm_opastaulu s
           join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
           where t.kunta_nro = $municipalityNumber
        """
      query.as[(Long, Long, Seq[Point], Int, String)].iterator().map {
        case (id, roadLinkId, geometry, sideCode, infoText) => Map("id" -> id, "point" -> geometry.head, "sideCode" -> sideCode, "infoText" -> infoText)
      }.toSeq
    }
  }

  def getRailwayCrossingsByMunicipality(municipalityNumber: Int): Seq[Map[String, Any]] = {
    Database.forDataSource(dataSource).withDynTransaction {
      val query = sql"""
         select s.segm_id, s.tielinkki_id, to_2d(sdo_lrs.dynamic_segment(t.shape, s.alkum, s.loppum)), s.puoli, s.varustus, s.nimi_s, s.nimi_r
           from segm_tasoristeys s
           join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
           where t.kunta_nro = $municipalityNumber
        """
      query.as[(Long, Long, Seq[Point], Int, Int, String, String)].iterator().map {
        case (id, roadLinkId, geometry, sideCode, props, nameFi, nameSv) =>
          Map("id" -> id, "point" -> geometry.head,
              "sideCode" -> sideCode, "props" -> props,
              "nameFi" -> nameFi, "nameSv" -> nameSv)
      }.toSeq
    }
  }
}
