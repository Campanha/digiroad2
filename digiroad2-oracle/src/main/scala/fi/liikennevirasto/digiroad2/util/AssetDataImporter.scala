package fi.liikennevirasto.digiroad2.util

import java.util.Properties
import javax.sql.DataSource

import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}

import scala.slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.oracle.{OracleSpatialAssetDao, Sequences}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.AssetDataImporter.{SimpleBusStop, _}
import fi.liikennevirasto.digiroad2.asset.oracle.Queries.updateAssetGeometry
import oracle.sql.STRUCT
import org.joda.time.LocalDate
import org.slf4j.LoggerFactory
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool
import scala.slick.jdbc.StaticQuery.interpolation
import scala.slick.jdbc._
import com.github.tototoshi.slick.MySQLJodaSupport._

import scala.slick.util.CloseableIterator

object AssetDataImporter {

  case class SimpleBusStop(shelterType: Int,
                           assetId: Option[Long] = None,
                           busStopId: Option[Long],
                           busStopType: Seq[Int],
                           lrmPositionId: Long,
                           validFrom: LocalDate = LocalDate.now,
                           validTo: Option[LocalDate] = None,
                           point: Point,
                           roadLinkId: Long,
                           municipalityCode: Int,
                           bearing: Double)
  case class SimpleRoadLink(id: Long, roadType: Int, roadNumber: Int, roadPartNumber: Int, functionalClass: Int, rStartHn: Int, lStartHn: Int,
                            rEndHn: Int, lEndHn: Int, municipalityNumber: Int, geom: STRUCT)

  case class PropertyWrapper(shelterTypePropertyId: Long, accessibilityPropertyId: Long, administratorPropertyId: Long,
                             busStopAssetTypeId: Long, busStopTypePropertyId: Long)

  sealed trait ImportDataSet {
    def database(): DatabaseDef
  }

  case object TemporaryTables extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/import.bonecp.properties"))
      new BoneCPDataSource(cfg)
    }

    def database() = Database.forDataSource(dataSource)
  }

  case object Conversion extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/conversion.bonecp.properties"))
      new BoneCPDataSource(cfg)
    }

    def database() = Database.forDataSource(dataSource)
    val roadLinkTable: String = "tielinkki"
    val busStopTable: String = "lineaarilokaatio"
  }
}

class AssetDataImporter {
  val logger = LoggerFactory.getLogger(getClass)
  lazy val ds: DataSource = initDataSource

  val Modifier = "dr1conversion"

  implicit object SetStruct extends SetParameter[STRUCT] {
    def apply(v: STRUCT, pp: PositionedParameters) {
      pp.setObject(v, java.sql.Types.STRUCT)
    }
  }

  def time[A](f: => A) = {
    val s = System.nanoTime
    val ret = f
    println("time for insert "+(System.nanoTime-s)/1e6+"ms")
    ret
  }

  private def getBatchDrivers(size: Int): List[(Int, Int)] = {
    println(s"""creating batching for $size items""")
    getBatchDrivers(1, size, 500)
  }

  private def getBatchDrivers(n: Int, m: Int, step: Int): List[(Int, Int)] = {
    if (m < step) {
      List((n, m))
    } else {
      val x = ((n to m by step).sliding(2).map(x => (x(0), x(1) - 1))).toList
      x :+ (x.last._2 + 1, m)
    }
  }

  var insertSpeedLimitsCount = 0;

  def importSpeedLimits(dataSet: ImportDataSet, taskPool: ForkJoinPool) = {

    val count = dataSet.database().withDynSession {
      sql"""
        select count(sl.segm_id)
          from SPEED_LIMITS_PRODUCTION sl
          """.as[Int].list.head
    }
    val min = dataSet.database().withDynSession {
      sql"""
        select min(segm_id)
          from SPEED_LIMITS_PRODUCTION sl
          order by sl.segm_id asc
          """.as[Int].list.head
    }
    val max = dataSet.database().withDynSession {
      sql"""
        select max(segm_id)
          from SPEED_LIMITS_PRODUCTION sl
          order by sl.segm_id desc
          """.as[Int].list.head
    }
    val startSelect = System.currentTimeMillis()

    val queries = getBatchDrivers(min, max, 500).view.map { case (n, m) =>
      sql"""
       select sl.SEGM_ID,
              stragg(sl.tielinkki_id || ';' || sl.alkum || ';' || sl.loppum || ';' || sl.nopeus || ';' || sl.puoli) insert_exprs
         from SPEED_LIMITS_PRODUCTION sl
         where segm_id between $n and $m
         group by sl.SEGM_ID
         order by sl.SEGM_ID
       """
    }.par

    queries.tasksupport = new ForkJoinTaskSupport(taskPool)
    queries.foreach { query =>
      val selectStartTime = System.currentTimeMillis()
      val segments = dataSet.database().withDynSession {
        query.as[(Int, String)].list()
      }
      Database.forDataSource(ds).withDynSession {
        val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, CREATED_DATE, CREATED_BY) values (?, 20, SYSDATE, 'dr1_conversion')")
        val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, ROAD_LINK_ID, START_MEASURE, END_MEASURE, SIDE_CODE) values (?, ?, ?, ?, ?)")
        val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")
        val speedLimitPS = dynamicSession.prepareStatement("insert into single_choice_value(asset_id, enumerated_value_id, property_id, modified_date, modified_by) values (?, (select id from enumerated_value where value = ? and property_id = (select id from property where public_id = 'rajoitus')), (select id from property where public_id = 'rajoitus'), sysdate, 'dr1_conversion')")

        segments.foreach { segment =>
          val assetId = Sequences.nextPrimaryKeySeqValue
          var speedLimit = -1
          assetPS.setLong(1, assetId)
          assetPS.addBatch()

          val insertExprs = segment._2.split("@").map(_.trim).filter(!_.isEmpty)
          insertExprs.foreach { insertExpr =>
            val insertValues = insertExpr.replace(',','.').split(";").toIterator
            val roadLinkId = insertValues.next.toLong
            val startMeasure = insertValues.next.toDouble
            val endMeasure   = insertValues.next.toDouble
            speedLimit = insertValues.next.toInt
            val sideCode = insertValues.next.toInt
            val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue

            lrmPositionPS.setLong(1, lrmPositionId)
            lrmPositionPS.setLong(2, roadLinkId)
            lrmPositionPS.setDouble(3, startMeasure)
            lrmPositionPS.setDouble(4, endMeasure)
            lrmPositionPS.setInt(5, sideCode)
            lrmPositionPS.addBatch()

            assetLinkPS.setLong(1, assetId)
            assetLinkPS.setLong(2, lrmPositionId)
            assetLinkPS.addBatch()

            this.synchronized {
              insertSpeedLimitsCount += 1
            }
          }
          speedLimitPS.setLong(1, assetId)
          if (Set(10, 15, 25).contains(speedLimit)) {
            speedLimit = 20
          } else if (speedLimit == 35) {
            speedLimit = 30
          } else if (speedLimit == 700) {
            speedLimit = 70
          }
          speedLimitPS.setInt(2, speedLimit)
          speedLimitPS.addBatch()
        }

        assetPS.executeBatch()
        lrmPositionPS.executeBatch()
        assetLinkPS.executeBatch()
        speedLimitPS.executeBatch()
        assetPS.close()
        lrmPositionPS.close()
        assetLinkPS.close()
        speedLimitPS.close()
      }

      this.synchronized {
        if (insertSpeedLimitsCount > 0) {
          val speedLimitsLeft = count-insertSpeedLimitsCount
          val timeNow = System.currentTimeMillis()
          val average =  (timeNow-startSelect)/insertSpeedLimitsCount
          val leftHours = speedLimitsLeft*average/(1000*60*60)
          val leftMins = speedLimitsLeft*average/(1000*60)%60
          printf("\r run time: %dmin | count: %d/%d %1.2f%% | average: %dms | time left: %dh %dmin | select time: %dms",
            (timeNow - startSelect)/60000,
            insertSpeedLimitsCount, count, insertSpeedLimitsCount/(count*1.0) * 100,
            average,
            leftHours, leftMins,
            timeNow - selectStartTime)
        }
      }
    }
  }

  def importTotalWeightLimits(database: DatabaseDef) = {
    importNumericalLimits(database, 22, 30)
  }

  def importNumericalLimits(database: DatabaseDef, sourceTypeId: Int, targetTypeId: Int): Unit = {
    val query =
      sql"""
       select segm_id, tielinkki_id, puoli, alkum, loppum, arvo
         from segments
         where tyyppi = $sourceTypeId
       """
    val numericalLimitLinks: Seq[(Long, Long, Int, Double, Double, Int)] = database.withDynSession {
      query.as[(Long, Long, Int, Double, Double, Int)].list()
    }
    val numericalLimits: Map[Long, Seq[(Long, Long, Int, Double, Double, Int)]] = numericalLimitLinks.groupBy(_._1)
    Database.forDataSource(ds).withDynTransaction {
      numericalLimits.foreach { numericalLimit ⇒
        val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, CREATED_DATE, CREATED_BY) values (?, ?, SYSDATE, 'dr1_conversion')")
        val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, ROAD_LINK_ID, START_MEASURE, END_MEASURE, SIDE_CODE) values (?, ?, ?, ?, ?)")
        val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")
        val numericalLimitPS = dynamicSession.prepareStatement("insert into number_property_value(id, asset_id, property_id, value) values (primary_key_seq.nextval, ?, (select id from property where public_id = 'mittarajoitus'), ?)")

        val (_, links) = numericalLimit
        val assetId = Sequences.nextPrimaryKeySeqValue
        assetPS.setLong(1, assetId)
        assetPS.setInt(2, targetTypeId)
        assetPS.addBatch()

        links.foreach { link ⇒
          val (_, roadLinkId, sideCode, startMeasure, endMeasure, _) = link
          val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
          lrmPositionPS.setLong(1, lrmPositionId)
          lrmPositionPS.setLong(2, roadLinkId)
          lrmPositionPS.setDouble(3, startMeasure)
          lrmPositionPS.setDouble(4, endMeasure)
          lrmPositionPS.setInt(5, sideCode)
          lrmPositionPS.addBatch()

          assetLinkPS.setLong(1, assetId)
          assetLinkPS.setLong(2, lrmPositionId)
          assetLinkPS.addBatch()
        }

        val limit = links.head._6
        numericalLimitPS.setLong(1, assetId)
        numericalLimitPS.setInt(2, limit)
        numericalLimitPS.addBatch()

        assetPS.executeBatch()
        lrmPositionPS.executeBatch()
        assetLinkPS.executeBatch()
        numericalLimitPS.executeBatch()
        assetPS.close()
        lrmPositionPS.close()
        assetLinkPS.close()
        numericalLimitPS.close()
      }
    }
  }

  def importManoeuvres(database: DatabaseDef): Unit = {
    throw new NotImplementedError("TODO: Fix importManoeuvres to fetch manoeuvre ids from manoeuvre sequence. Current implementation imports manoeuvres with clashing ids")
    val query =
      sql"""
        select kaan_id, kaan_tyyppi, tl_dr1_id, elem_jarjestyslaji
          from kaantymismaarays
        """
    val manoeuvres: Seq[(Long, Int, Long, Int)] = database.withDynSession {
      query.as[(Long, Int, Long, Int)].list()
    }
    Database.forDataSource(ds).withDynTransaction {
      manoeuvres.foreach { manoeuvre =>
        val (id, manoeuvreType, roadLinkId, elementType) = manoeuvre
        sqlu"""
          insert into manoeuvre (id, type, road_link_id, element_type, modified_by)
          values ($id, $manoeuvreType, $roadLinkId, $elementType, 'dr1_conversion')
          """.execute()
      }
    }
  }

  def getTypeProperties = {
    Database.forDataSource(ds).withDynSession {
      val shelterTypePropertyId = sql"select p.id from property p where p.public_id = 'katos'".as[Long].first
      val accessibilityPropertyId = sql"select p.id from property p where p.public_id = 'esteettomyys_liikuntarajoitteiselle'".as[Long].first
      val administratorPropertyId = sql"select p.id from property p where p.public_id = 'tietojen_yllapitaja'".as[Long].first
      val busStopTypePropertyId = sql"select p.id from property p where p.public_id = 'pysakin_tyyppi'".as[Long].first
      val busStopAssetTypeId = sql"select id from asset_type where name = 'Bussipysäkit'".as[Long].first
      PropertyWrapper(shelterTypePropertyId, accessibilityPropertyId, administratorPropertyId,
                      busStopAssetTypeId, busStopTypePropertyId)
    }
  }

  def insertBusStops(busStop: SimpleBusStop, typeProps: PropertyWrapper) {
    Database.forDataSource(ds).withDynSession {
      val assetId = busStop.assetId.getOrElse(Sequences.nextPrimaryKeySeqValue)

      sqlu"""
        insert into asset(id, external_id, asset_type_id, created_by, valid_from, valid_to, municipality_code, bearing)
        values($assetId, ${busStop.busStopId}, ${typeProps.busStopAssetTypeId},
               $Modifier, ${busStop.validFrom}, ${busStop.validTo.getOrElse(null)},
               ${busStop.municipalityCode}, ${busStop.bearing})
      """.execute

      sqlu"""
         insert into asset_link(asset_id, position_id)
         values($assetId, ${busStop.lrmPositionId})
      """.execute

      updateAssetGeometry(assetId, busStop.point)
      busStop.busStopType.foreach { busStopType =>
        insertMultipleChoiceValue(typeProps.busStopTypePropertyId, assetId, busStopType)
      }

      insertTextPropertyData(typeProps.accessibilityPropertyId, assetId, "Ei tiedossa")
      insertSingleChoiceValue(typeProps.administratorPropertyId, assetId, 2)
      insertSingleChoiceValue(typeProps.shelterTypePropertyId, assetId, busStop.shelterType)
    }
  }

  def importMMLIdsOnMassTransitStops(conversionDB: DatabaseDef) {
    Database.forDataSource(ds).withSession { dbSession =>
      conversionDB.withSession { conversionSession =>
        val municipalityCodes: CloseableIterator[Int] = sql"""select id from municipality""".as[Int].iterator()(dbSession)
        municipalityCodes.foreach { municipalityCode =>
          println(s"Importing MML IDs on mass transit stops in municipality: $municipalityCode")
          val roadLinkIds: CloseableIterator[(Long, Long, Option[Long], Option[Long])] =
            sql"""select a.id, lrm.id, lrm.road_link_id, lrm.prod_road_link_id
                from asset a
                join asset_link al on a.id = al.asset_id
                join lrm_position lrm on lrm.id = al.position_id
                where a.asset_type_id = 10 and a.municipality_code = $municipalityCode"""
              .as[(Long, Long, Option[Long], Option[Long])].iterator()(dbSession)
          val mmlIds: CloseableIterator[(Long, Long, Option[Long])] =
            roadLinkIds.map { roadLinkId =>
              val (assetId, lrmId, testRoadLinkId, productionRoadLinkId) = roadLinkId
              val mmlId: Option[Long] = (testRoadLinkId, productionRoadLinkId) match {
                case (_, Some(prodId)) => sql"""select mml_id from tielinkki_ctas where dr1_id = $prodId""".as[Long].firstOption()(conversionSession)
                case (Some(testId), None) => sql"""select mml_id from tielinkki where objectid = $testId""".as[Long].firstOption()(conversionSession)
                case _ => None
              }
              (assetId, lrmId, mmlId)
            }
          dbSession.withTransaction {
            mmlIds.foreach { case (assetId, lrmId, mmlId) =>
              sqlu"""update lrm_position set mml_id = $mmlId where id = $lrmId""".execute()(dbSession)
              if (mmlId.isEmpty) {
                sqlu"""update asset set floating = 1 where id = $assetId""".execute()(dbSession)
              }
            }
          }
        }
      }
    }
  }

  def importMMLIdsOnNumericalLimit(conversionDB: DatabaseDef, assetTypeId: Int) {
    Database.forDataSource(ds).withSession { dbSession =>
      conversionDB.withSession { conversionSession =>
        val roadLinkIds: CloseableIterator[(Long, Long, Option[Long])] =
          sql"""select a.id, lrm.id, lrm.road_link_id
                from asset a
                join asset_link al on a.id = al.asset_id
                join lrm_position lrm on lrm.id = al.position_id
                where a.asset_type_id = $assetTypeId"""
            .as[(Long, Long, Option[Long])].iterator()(dbSession)
        val mmlIds: CloseableIterator[(Long, Long, Option[Long])] =
          roadLinkIds.map { roadLink =>
            val (assetId, lrmId, roadLinkId) = roadLink
            val mmlId: Option[Long] = roadLinkId match {
              case Some(dr1Id) => sql"""select mml_id from tielinkki_ctas where dr1_id = $dr1Id""".as[Long].firstOption()(conversionSession)
              case _ => None
            }
            (assetId, lrmId, mmlId)
          }
        dbSession.withTransaction {
          mmlIds.foreach { case (assetId, lrmId, mmlId) =>
            sqlu"""update lrm_position set mml_id = $mmlId where id = $lrmId""".execute()(dbSession)
          }
        }
      }
    }
  }

  def insertTextPropertyData(propertyId: Long, assetId: Long, text:String) {
    sqlu"""
      insert into text_property_value(id, property_id, asset_id, value_fi, value_sv, created_by)
      values (primary_key_seq.nextval, $propertyId, $assetId, $text, ' ', $Modifier)
    """.execute
  }

  def insertMultipleChoiceValue(propertyId: Long, assetId: Long, value: Int) {
    sqlu"""
      insert into multiple_choice_value(id, property_id, asset_id, enumerated_value_id, modified_by)
      values (primary_key_seq.nextval, $propertyId, $assetId,
        (select id from enumerated_value where value = $value and property_id = $propertyId), $Modifier)
    """.execute
  }

  def insertSingleChoiceValue(propertyId: Long, assetId: Long, value: Int) {
    sqlu"""
      insert into single_choice_value(property_id, asset_id, enumerated_value_id, modified_by)
      values ($propertyId, $assetId, (select id from enumerated_value where value = $value and property_id = $propertyId), $Modifier)
    """.execute
  }

  private[this] def initDataSource: DataSource = {
    Class.forName("oracle.jdbc.driver.OracleDriver")
    val cfg = new BoneCPConfig(localProperties)
    new BoneCPDataSource(cfg)
  }

  lazy val localProperties: Properties = {
    val props = new Properties()
    try {
      props.load(getClass.getResourceAsStream("/bonecp.properties"))
    } catch {
      case e: Exception => throw new RuntimeException("Can't load local.properties for env: " + System.getProperty("env"), e)
    }
    props
  }
}
