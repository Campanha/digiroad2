package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.VVHClient
import fi.liikennevirasto.digiroad2.masstransitstop.oracle.Queries
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession

object LinkIdImporter {
  def withDynTransaction(f: => Unit): Unit = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  def importLinkIdsFromVVH(vvhHost: String): Unit = {
    val vvhClient = new VVHClient(vvhHost)
    val municipalities = withDynSession { Queries.getMunicipalities }
    val total = municipalities.size

    withDynTransaction {
      municipalities.zipWithIndex.foreach { case (municipalityCode, i) =>
        val startTime = DateTime.now()

        val roadlinks = vvhClient.fetchByMunicipality(municipalityCode)

        roadlinks.foreach { link =>
          sqlu"""
            update lrm_position
            set link_id = ${link.linkId}
            where mml_id = ${link.mmlId}
          """.execute
          sqlu"""
            update traffic_direction
            set link_id = ${link.linkId}
            where mml_id = ${link.mmlId}
          """.execute
          sqlu"""
            update incomplete_link
            set link_id = ${link.linkId}
            where mml_id = ${link.mmlId}
          """.execute
          sqlu"""
            update functional_class
            set link_id = ${link.linkId}
            where mml_id = ${link.mmlId}
          """.execute
          sqlu"""
            update link_type
            set link_id = ${link.linkId}
            where mml_id = ${link.mmlId}
          """.execute
          sqlu"""
            update manoeuvre_element
            set link_id = ${link.linkId}
            where mml_id = ${link.mmlId}
          """.execute
          sqlu"""
            update unknown_speed_limit
            set link_id = ${link.linkId}
            where mml_id = ${link.mmlId}
          """.execute

        }

        println(s"Imported ${roadlinks.size} link IDs for municipality $municipalityCode (${i+1}/$total) in ${AssetDataImporter.humanReadableDurationSince(startTime)}")
      }
    }
  }
}
