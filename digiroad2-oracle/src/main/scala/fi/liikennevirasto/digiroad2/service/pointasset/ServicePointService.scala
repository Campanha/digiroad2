package fi.liikennevirasto.digiroad2.service.pointasset

import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Queries
import fi.liikennevirasto.digiroad2.dao.pointasset.{IncomingServicePoint, OracleServicePointDao, ServicePoint}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery
import slick.jdbc.StaticQuery.interpolation
import com.github.tototoshi.slick.MySQLJodaSupport._
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

class ServicePointService {
  val typeId: Int = 250

  def create(asset: IncomingServicePoint, municipalityCode: Int, username: String) = {
    withDynTransaction {
      OracleServicePointDao.create(asset, municipalityCode, username)
    }
  }

  def update(id: Long, updatedAsset: IncomingServicePoint, municipalityCode: Int, username: String): Long = {
    withDynTransaction {
      OracleServicePointDao.update(id, updatedAsset, municipalityCode, username)
    }
  }

  def expire(id: Long, username: String): Long = {
    withDynTransaction {
      OracleServicePointDao.expire(id, username)
    }
  }

  def get: Set[ServicePoint] = {
    withDynSession {
      OracleServicePointDao.get
    }
  }

  def getById(id: Long): ServicePoint = {
    withDynSession {
      OracleServicePointDao.getById(id).headOption.getOrElse(throw new NoSuchElementException("Asset not found"))
    }
  }


  def getByMunicipality(municipalityNumber: Int): Set[ServicePoint] = {
    withDynSession {
      OracleServicePointDao.getByMunicipality(municipalityNumber)
    }
  }

  def get(boundingBox: BoundingRectangle): Set[ServicePoint] = {
    withDynSession {
      OracleServicePointDao.get(boundingBox)
    }
  }

  lazy val dataSource = {
    val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/bonecp.properties"))
    new BoneCPDataSource(cfg)
  }

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  def getPersistedAssetsByIds(ids: Set[Long]): Set[ServicePoint] = {
    withDynSession {
      val idsStr = ids.toSeq.mkString(",")
      val filter = s"a.id in ($idsStr)"
      fetchPointAssets(filter)
    }
  }

  def fetchPointAssets(filter: String): Set[ServicePoint] = OracleServicePointDao.fetchByFilter(filter)

}


