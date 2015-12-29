package fi.liikennevirasto.digiroad2

import _root_.oracle.jdbc.OracleData
import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.pointasset.oracle.{IncomingServicePoint, OracleServicePointDao, ServicePoint}
import fi.liikennevirasto.digiroad2.user.User

class ServicePointService {

  val typeId: Int = 250

  def create(asset: IncomingServicePoint, municipalityCode: Int, username: String) = {
    OracleDatabase.withDynTransaction {
      OracleServicePointDao.create(asset, municipalityCode, username)
    }
  }

  def update(id: Long, updatedAsset: IncomingServicePoint, municipalityCode: Int, username: String) = {
    OracleDatabase.withDynTransaction {
      OracleServicePointDao.update(id, updatedAsset, municipalityCode, username)
    }
  }

  def expire(id: Long, username: String) = {
    OracleDatabase.withDynTransaction {
      OracleServicePointDao.expire(id, username)
    }
  }

  def get: Set[ServicePoint] = {
    OracleDatabase.withDynSession {
      OracleServicePointDao.get
    }
  }

  def get(boundingBox: BoundingRectangle): Set[ServicePoint] = {
    OracleDatabase.withDynSession {
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
    OracleDatabase.withDynSession {
      val idsStr = ids.toSeq.mkString(",")
      val filter = s"a.id in ($idsStr)"
      fetchPointAssets(filter)
    }
  }

  def fetchPointAssets(filter: String): Set[ServicePoint] = OracleServicePointDao.fetchByFilter(filter)

}


