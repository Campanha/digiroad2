package fi.liikennevirasto.digiroad2.roadaddress.oracle

import fi.liikennevirasto.digiroad2.FeatureClass.TractorRoad
import fi.liikennevirasto.digiroad2.asset.Asset.DateTimePropertyFormat
import fi.liikennevirasto.digiroad2.asset.CycleOrPedestrianPath
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, RoadLinkService}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.VVHSerializer
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory

case class ChangedRoadAddress(roadAddress : RoadAddress, link: RoadLink)

class RoadAddressesService(val eventbus: DigiroadEventBus, roadLinkServiceImplementation: RoadLinkService) {

  val logger = LoggerFactory.getLogger(getClass)

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  def getChanged(startDate: DateTime): Seq[ChangedRoadAddress] = {
    val roadAddressDAO = new RoadAddressDAO()

    val roadAddresses =
      withDynTransaction {
        roadAddressDAO.getRoadAddress(roadAddressDAO.withStartDate(startDate))
      }

    val roadLinks = roadLinkServiceImplementation.getRoadLinksAndComplementaryByLinkIdsFromVVH(roadAddresses.map(_.linkId).toSet)
    val roadLinksWithoutWalkways = roadLinks.filterNot(_.linkType == CycleOrPedestrianPath).filterNot(_.linkType == TractorRoad)

    roadAddresses.flatMap { roadAddress =>
      roadLinksWithoutWalkways.find(_.linkId == roadAddress.linkId).map { roadLink =>
        ChangedRoadAddress(
          roadAddress = RoadAddress(
            id = roadAddress.id,
            roadNumber = roadAddress.roadNumber,
            roadPartNumber = roadAddress.roadPartNumber,
            track = roadAddress.track,
            discontinuity = roadAddress.discontinuity,
            startAddrMValue = roadAddress.startAddrMValue,
            endAddrMValue = roadAddress.endAddrMValue,
            startDate = roadAddress.startDate,
            endDate = roadAddress.endDate,
            lrmPositionId = roadAddress.lrmPositionId,
            linkId = roadAddress.linkId,
            startMValue = roadAddress.startMValue,
            endMValue = roadAddress.endMValue,
            sideCode = roadAddress.sideCode,
            floating = roadAddress.floating,
            geom = GeometryUtils.truncateGeometry3D(roadLink.geometry, roadAddress.startMValue, roadAddress.endMValue),
            expired = roadAddress.expired,
            createdBy = roadAddress.createdBy,
            createdDate = roadAddress.createdDate,
            modifiedDate = roadAddress.modifiedDate
          ),
          link = roadLink
        )
      }
    }
  }
}
