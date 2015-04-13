package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.user.{Configuration, User}
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

import scala.slick.driver.JdbcDriver.backend.Database
import scala.slick.driver.JdbcDriver.backend.Database.dynamicSession
import scala.slick.jdbc.StaticQuery.interpolation
import scala.slick.jdbc.{StaticQuery => Q}

class MassTransitStopServiceSpec extends FunSuite with Matchers {
  val boundingBoxWithKauniainenAssets = BoundingRectangle(Point(374000,6677000), Point(374800,6677600))
  val userWithKauniainenAuthorization = User(
    id = 1,
    username = "Hannu",
    configuration = Configuration(authorizedMunicipalities = Set(235)))
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  when(mockRoadLinkService.fetchVVHRoadlinks(any[BoundingRectangle], any[Set[Int]])).thenReturn(List((1140018963l, 90, Nil), (388554364l, 235, List(Point(0.0,0.0), Point(120.0, 0.0)))))
  when(mockRoadLinkService.fetchVVHRoadlink(388554364l)).thenReturn(Some((235, List(Point(0.0,0.0), Point(120.0, 0.0)))))
  when(mockRoadLinkService.fetchVVHRoadlink(123l)).thenReturn(Some((91, List(Point(0.0,0.0), Point(120.0, 0.0)))))
  when(mockRoadLinkService.fetchVVHRoadlink(388553080l)).thenReturn(Some((235, Nil)))

  object RollbackMassTransitStopService extends MassTransitStopService {
    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
    val roadLinkService: RoadLinkService = mockRoadLinkService
  }

  def runWithCleanup(test: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      test
      dynamicSession.rollback()
    }
  }

  test("Calculate mass transit stop validity periods") {
    runWithCleanup {
      val massTransitStops = RollbackMassTransitStopService.getByBoundingBox(userWithKauniainenAuthorization, boundingBoxWithKauniainenAssets)
      massTransitStops.find(_.id == 300000).map(_.validityPeriod) should be(Some(ValidityPeriod.Current))
      massTransitStops.find(_.id == 300001).map(_.validityPeriod) should be(Some(ValidityPeriod.Past))
      massTransitStops.find(_.id == 300003).map(_.validityPeriod) should be(Some(ValidityPeriod.Future))
    }
  }

  test("Return mass transit stop types") {
    runWithCleanup {
      val massTransitStops = RollbackMassTransitStopService.getByBoundingBox(userWithKauniainenAuthorization, boundingBoxWithKauniainenAssets)
      massTransitStops.find(_.id == 300000).map(_.stopTypes) should be(Some(Seq(2)))
      massTransitStops.find(_.id == 300001).map(_.stopTypes) should be(Some(Seq(2, 3, 4)))
      massTransitStops.find(_.id == 300003).map(_.stopTypes) should be(Some(Seq(2, 3)))
    }
  }

  test("Get stops by bounding box") {
    runWithCleanup {
      val stops = RollbackMassTransitStopService.getByBoundingBox(userWithKauniainenAuthorization, BoundingRectangle(Point(374443, 6677245), Point(374444, 6677246)))
      stops.size shouldBe 1
    }
  }

  test("Filter stops by authorization") {
    runWithCleanup {
      val stops = RollbackMassTransitStopService.getByBoundingBox(User(0, "test", Configuration()), boundingBoxWithKauniainenAssets)
      stops should be(empty)
    }
  }

  test("Stop floats if road link does not exist") {
    runWithCleanup {
      val stops = RollbackMassTransitStopService.getByBoundingBox(userWithKauniainenAuthorization, boundingBoxWithKauniainenAssets)
      stops.find(_.id == 300000).map(_.floating) should be(Some(true))
    }
  }

  test("Stop floats if stop and roadlink municipality codes differ") {
    runWithCleanup {
      val stops = RollbackMassTransitStopService.getByBoundingBox(userWithKauniainenAuthorization, boundingBoxWithKauniainenAssets)
      stops.find(_.id == 300004).map(_.floating) should be(Some(true))
    }
  }

  test("Stop floats if stop is too far from linearly referenced location") {
    runWithCleanup {
      val stops = RollbackMassTransitStopService.getByBoundingBox(userWithKauniainenAuthorization, boundingBoxWithKauniainenAssets)
      stops.find(_.id == 300008).map(_.floating) should be(Some(true))
    }
  }

  test("Persist mass transit stop floating status change") {
    runWithCleanup {
      RollbackMassTransitStopService.getByBoundingBox(userWithKauniainenAuthorization, boundingBoxWithKauniainenAssets)
      val floating: Option[Boolean] = sql"""select floating from asset where id = 300008""".as[Boolean].firstOption()
      floating should be(Some(true))
    }
  }

  test("Fetch mass transit stop by national id") {
    runWithCleanup {
      val stop = RollbackMassTransitStopService.getByNationalId(85755, _ => Unit)
      stop.map(_.floating) should be(Some(true))
    }
  }

  test("Assert user rights when fetching mass transit stop with id") {
    runWithCleanup {
      an [Exception] should be thrownBy RollbackMassTransitStopService.getByNationalId(85755, { municipalityCode => throw new Exception })
    }
  }

  test("Update mass transit stop road link mml id") {
    runWithCleanup {
      val position = Some(Position(60.0, 0.0, 388554364l, None))
      RollbackMassTransitStopService.updatePosition(300000, position, Nil, "user", _ => Unit)
      val mmlId = sql"""
            select lrm.mml_id from asset a
            join asset_link al on al.asset_id = a.id
            join lrm_position lrm on lrm.id = al.position_id
            where a.id = 300000
      """.as[Long].firstOption()
      mmlId should be(Some(388554364l))
    }
  }

  test("Update mass transit stop bearing") {
    runWithCleanup {
      val position = Some(Position(60.0, 0.0, 388554364l, Some(90)))
      RollbackMassTransitStopService.updatePosition(300000, position, Nil, "user", _ => Unit)
      val bearing = sql"""
            select a.bearing from asset a
            join asset_link al on al.asset_id = a.id
            join lrm_position lrm on lrm.id = al.position_id
            where a.id = 300000
      """.as[Option[Int]].first()
      bearing should be(Some(90))
    }
  }

  test("Update mass transit stop municipality") {
    runWithCleanup {
      val position = Some(Position(60.0, 0.0, 123l, None))
      RollbackMassTransitStopService.updatePosition(300000, position, Nil, "user", _ => Unit)
      val municipality = sql"""
            select a.municipality_code from asset a
            join asset_link al on al.asset_id = a.id
            join lrm_position lrm on lrm.id = al.position_id
            where a.id = 300000
      """.as[Int].firstOption()
      municipality should be(Some(91))
    }
  }

  test("Update last modified info") {
    runWithCleanup {
      RollbackMassTransitStopService.updatePosition(300000, None, Nil, "user", _ => Unit)
      val modifier = sql"""
            select a.modified_by from asset a
            where a.id = 300000
      """.as[String].firstOption()
      modifier should be(Some("user"))
    }
  }

  test("Update properties") {
    runWithCleanup {
      val values = List(PropertyValue("New name"))
      val properties = List(SimpleProperty("nimi_suomeksi", values))
      RollbackMassTransitStopService.updatePosition(300000, None, properties, "user", _ => Unit)
      val modifier = sql"""
            select v.value_fi from text_property_value v
            join property p on v.property_id = p.id
            where v.asset_id = 300000 and p.public_id = 'nimi_suomeksi'
      """.as[String].firstOption()
      modifier should be(Some("New name"))
    }
  }

  test("Persist floating on update") {
    runWithCleanup {
      val position = Some(Position(60.0, 0.0, 123l, None))
      RollbackMassTransitStopService.updatePosition(300002, position, Nil, "user", _ => Unit)
      val floating = sql"""
            select a.floating from asset a
            where a.id = 300002
      """.as[Int].firstOption()
      floating should be(Some(0))
    }
  }

  test("Assert user rights when updating a mass transit stop") {
    runWithCleanup {
      val position = Some(Position(60.0, 0.0, 123l, None))
      an [Exception] should be thrownBy RollbackMassTransitStopService.updatePosition(300002, position, Nil, "user", { municipalityCode => throw new Exception })
    }
  }

  test("Create new mass transit stop") {
    runWithCleanup {
      val values = List(PropertyValue("1"))
      val properties = List(SimpleProperty("pysakin_tyyppi", values))
      val massTransitStop = RollbackMassTransitStopService.createNew(60.0, 0.0, 123l, 100, "test", properties)
      massTransitStop.bearing should be(Some(100))
      massTransitStop.floating should be(false)
      massTransitStop.stopTypes should be(List(1))
      massTransitStop.validityPeriod should be(Some(ValidityPeriod.Current))
    }
  }

  test("Calculate linear reference point") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val point: Point = MassTransitStopService.calculatePointFromLinearReference(linkGeometry, 0.5).get
    point.x should be(0.5)
    point.y should be(0.0)
  }

  test("Calculate linear reference point on three-point geometry") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val point: Point = MassTransitStopService.calculatePointFromLinearReference(linkGeometry, 1.5).get
    point.x should be(1.0)
    point.y should be(0.5)
  }

  test("Linear reference point on less than two-point geometry should be undefined") {
    val linkGeometry = Nil
    val point: Option[Point] = MassTransitStopService.calculatePointFromLinearReference(linkGeometry, 1.5)
    point should be(None)
  }

  test("Linear reference point on negative measurement should be undefined") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val point: Option[Point] = MassTransitStopService.calculatePointFromLinearReference(linkGeometry, -1.5)
    point should be(None)
  }

  test("Linear reference point outside geometry should be undefined") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val point: Option[Point] = MassTransitStopService.calculatePointFromLinearReference(linkGeometry, 1.5)
    point should be(None)
  }

  test("Project stop location on two-point geometry") {
    val linkGeometry: Seq[Point] = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val location: Point = Point(0.5, 0.5)
    val mValue: Double = MassTransitStopService.calculateLinearReferenceFromPoint(location, linkGeometry)
    mValue should be(0.5)
  }

  test("Project stop location on three-point geometry") {
    val linkGeometry: Seq[Point] = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 0.5))
    val location: Point = Point(1.2, 0.25)
    val mValue: Double = MassTransitStopService.calculateLinearReferenceFromPoint(location, linkGeometry)
    mValue should be(1.25)
  }

  test("Project stop location to beginning of geometry if point lies behind geometry") {
    val linkGeometry: Seq[Point] = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val location: Point = Point(-0.5, 0.0)
    val mValue: Double = MassTransitStopService.calculateLinearReferenceFromPoint(location, linkGeometry)
    mValue should be(0.0)
  }

  test("Project stop location to the end of geometry if point lies beyond geometry") {
    val linkGeometry: Seq[Point] = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val location: Point = Point(1.5, 0.5)
    val mValue: Double = MassTransitStopService.calculateLinearReferenceFromPoint(location, linkGeometry)
    mValue should be(1.0)
  }
}
