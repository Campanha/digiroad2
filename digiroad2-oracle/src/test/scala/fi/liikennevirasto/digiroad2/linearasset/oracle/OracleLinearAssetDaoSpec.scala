package fi.liikennevirasto.digiroad2.linearasset.oracle

import fi.liikennevirasto.digiroad2.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.{VVHRoadlink, GeometryUtils, RoadLinkService, Point}
import fi.liikennevirasto.digiroad2.asset.{UnknownDirection, Municipality, BoundingRectangle}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase._
import fi.liikennevirasto.digiroad2.asset.oracle.Queries._
import oracle.jdbc.OracleConnection

import org.scalatest.FunSuite
import org.scalatest.Matchers
import org.scalatest.Tag

import oracle.spatial.geometry.JGeometry
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import scala.slick.jdbc.{StaticQuery => Q}
import Q.interpolation
import scala.slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession

class OracleLinearAssetDaoSpec extends FunSuite with Matchers {
  val mockedRoadLinkService = MockitoSugar.mock[RoadLinkService]

  val roadLink = VVHRoadlink(388562360, 0, List(Point(0.0, 0.0), Point(0.0, 200.0)), Municipality, UnknownDirection, AllOthers)
  val roadLink4 = VVHRoadlink(362964776, 0, List(Point(0.0, 0.0), Point(0.0, 90.733)), Municipality, UnknownDirection, AllOthers)
  val roadLink2 = VVHRoadlink(362959407, 0, List(Point(0.0, 90.733), Point(0.0, 245.119)), Municipality, UnknownDirection, AllOthers)
  val roadLink3 = VVHRoadlink(362959521, 0, List(Point(0.0, 245.119), Point(0.0, 470.0)), Municipality, UnknownDirection, AllOthers)

  val roadLink6 = VVHRoadlink(388552162, 0, List(Point(0.0, 0.0), Point(24.011, 0.0)), Municipality, UnknownDirection, AllOthers)
  val roadLink9 = VVHRoadlink(388552168, 0, List(Point(24.011, 0.0), Point(115.237, 0.0)), Municipality, UnknownDirection, AllOthers)
  val roadLink8 = VVHRoadlink(388569874, 0, List(Point(282.894, 0.0), Point(115.237, 0.0)), Municipality, UnknownDirection, AllOthers)
  val roadLink5 = VVHRoadlink(362956845, 0, List(Point(282.894, 0.0), Point(568.899, 0.0)), Municipality, UnknownDirection, AllOthers)
  val roadLink7 = VVHRoadlink(388552348, 0, List(Point(568.899, 0.0), Point(583.881, 0.0)), Municipality, UnknownDirection, AllOthers)
  val roadLink10 = VVHRoadlink(388552354, 0, List(Point(583.881, 0.0), Point(716.0, 0.0)), Municipality, UnknownDirection, AllOthers)

  when(mockedRoadLinkService.fetchVVHRoadlink(388562360)).thenReturn(Some(roadLink))
  when(mockedRoadLinkService.fetchVVHRoadlink(362959407)).thenReturn(Some(roadLink2))
  when(mockedRoadLinkService.fetchVVHRoadlink(362959521)).thenReturn(Some(roadLink3))
  when(mockedRoadLinkService.fetchVVHRoadlink(362964776)).thenReturn(Some(roadLink4))

  when(mockedRoadLinkService.fetchVVHRoadlink(362956845)).thenReturn(Some(roadLink5))
  when(mockedRoadLinkService.fetchVVHRoadlink(388552162)).thenReturn(Some(roadLink6))
  when(mockedRoadLinkService.fetchVVHRoadlink(388552348)).thenReturn(Some(roadLink7))
  when(mockedRoadLinkService.fetchVVHRoadlink(388569874)).thenReturn(Some(roadLink8))
  when(mockedRoadLinkService.fetchVVHRoadlink(388552168)).thenReturn(Some(roadLink9))
  when(mockedRoadLinkService.fetchVVHRoadlink(388552354)).thenReturn(Some(roadLink10))

  val dao = new OracleLinearAssetDao {
    override val roadLinkService: RoadLinkService = mockedRoadLinkService
  }

  private def truncateLinkGeometry(mmlId: Long, startMeasure: Double, endMeasure: Double): Seq[Point] = {
    val geometry = mockedRoadLinkService.fetchVVHRoadlink(mmlId).get.geometry
    GeometryUtils.truncateGeometry(geometry, startMeasure, endMeasure)
  }

  def assertSpeedLimitEndPointsOnLink(speedLimitId: Long, mmlId: Long, startMeasure: Double, endMeasure: Double) = {
    val expectedEndPoints = GeometryUtils.geometryEndpoints(truncateLinkGeometry(mmlId, startMeasure, endMeasure).toList)
    val limitEndPoints = GeometryUtils.geometryEndpoints(dao.getLinksWithLengthFromVVH(20, speedLimitId).find { link => link._1 == mmlId }.get._3)
    expectedEndPoints._1.distanceTo(limitEndPoints._1) should be(0.0 +- 0.01)
    expectedEndPoints._2.distanceTo(limitEndPoints._2) should be(0.0 +- 0.01)
  }

  test("splitting one link speed limit " +
    "where split measure is after link middle point " +
    "modifies end measure of existing speed limit " +
    "and creates new speed limit for second split", Tag("db")) {
    Database.forDataSource(ds).withDynTransaction {
      val createdId = dao.splitSpeedLimit(200097, 388562360, 100, 120, "test")
      val (existingModifiedBy, _, _, _, _) = dao.getSpeedLimitDetails(200097)
      val (_, _, newCreatedBy, _, _) = dao.getSpeedLimitDetails(createdId)

      assertSpeedLimitEndPointsOnLink(200097, 388562360, 0, 100)
      assertSpeedLimitEndPointsOnLink(createdId, 388562360, 100, 136.788)

      existingModifiedBy shouldBe Some("test")
      newCreatedBy shouldBe Some("test")
      dynamicSession.rollback()
    }
  }

  test("splitting one link speed limit " +
    "where split measure is before link middle point " +
    "modifies start measure of existing speed limit " +
    "and creates new speed limit for first split", Tag("db")) {
    Database.forDataSource(ds).withDynTransaction {
      val createdId = dao.splitSpeedLimit(200097, 388562360, 50, 120, "test")
      val (modifiedBy, _, _, _, _) = dao.getSpeedLimitDetails(200097)
      val (_, _, newCreatedBy, _, _) = dao.getSpeedLimitDetails(createdId)

      assertSpeedLimitEndPointsOnLink(200097, 388562360, 50, 136.788)
      assertSpeedLimitEndPointsOnLink(createdId, 388562360, 0, 50)

      modifiedBy shouldBe Some("test")
      newCreatedBy shouldBe Some("test")
      dynamicSession.rollback()
    }
  }

  test("splitting three link speed limit " +
    "where first split is shorter than second " +
    "existing speed limit should cover only second split", Tag("db")) {
    Database.forDataSource(ds).withDynTransaction {
      dao.splitSpeedLimit(200217, 362959407, 10, 120, "test")
      val existingLinks = dao.getLinksWithLengthFromVVH(20, 200217)

      existingLinks.length shouldBe 2
      existingLinks.map(_._1) should contain only (362959407, 362959521)
      dynamicSession.rollback()
    }
  }

  ignore("splitting speed limit " +
    "so that shorter split contains multiple linear references " +
    "moves all linear references to newly created speed limit", Tag("db")) {
    Database.forDataSource(ds).withDynTransaction {
      val createdId = dao.splitSpeedLimit(200363, 388569874, 148, 120, "test")
      val createdLinks = dao.getLinksWithLengthFromVVH(20, createdId)

      createdLinks.length shouldBe 3
      createdLinks.map(_._1) should contain only (388552162, 388552168, 388569874)
      dynamicSession.rollback()
    }
  }


}
