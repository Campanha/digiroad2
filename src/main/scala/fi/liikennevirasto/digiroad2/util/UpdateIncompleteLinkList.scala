package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.Digiroad2Context
import fi.liikennevirasto.digiroad2.asset.oracle.OracleSpatialAssetDao
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object UpdateIncompleteLinkList {
  def main(args:Array[String]) : Unit = {
    try {
      UpdateIncompleteLinkList.runUpdate()
    } catch {
      case e: Exception => e.printStackTrace()
    }
    Digiroad2Context.system.shutdown()
  }

  private def runUpdate(): Unit = {
    println("*** Delete incomplete links")
    clearIncompleteLinks()
    println("*** Get municipalities")
    val municipalities: Seq[Int] = OracleDatabase.withDynSession {
      new OracleSpatialAssetDao().getMunicipalities
    }
    municipalities.foreach { municipality =>
      println("*** Processing municipality: " + municipality)
      val roadLinks = Digiroad2Context.roadLinkService.getRoadLinksFromVVH(municipality)
      println("*** Processed " + roadLinks.length + " road links")
    }
  }

  private def clearIncompleteLinks(): Unit = {
    OracleDatabase.withDynTransaction {
      sqlu"""truncate table incomplete_link""".execute
    }
  }
}
