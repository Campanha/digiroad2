package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.asset.oracle.Queries._
import fi.liikennevirasto.digiroad2.asset.oracle.{Sequences, AssetPropertyConfiguration, LRMPosition, OracleSpatialAssetDao}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.user.User
import org.joda.time.{DateTime, Interval, LocalDate}

import scala.slick.driver.JdbcDriver.backend.Database.dynamicSession
import scala.slick.jdbc.StaticQuery.interpolation
import scala.slick.jdbc.{GetResult, PositionedResult, StaticQuery}

trait FloatingStop {
  val id: Long
  val floating: Boolean
}

case class MassTransitStop(id: Long, nationalId: Long, lon: Double, lat: Double, bearing: Option[Int],
                           validityDirection: Int, municipalityNumber: Int,
                           validityPeriod: String, floating: Boolean, stopTypes: Seq[Int])

case class MassTransitStopWithProperties(id: Long, nationalId: Long, stopTypes: Seq[Int], lon: Double, lat: Double,
                              validityDirection: Option[Int], bearing: Option[Int],
                              validityPeriod: Option[String], floating: Boolean,
                              propertyData: Seq[Property]) extends FloatingStop

case class MassTransitStopWithTimeStamps(id: Long, nationalId: Long, lon: Double, lat: Double,
                              bearing: Option[Int], floating: Boolean,
                              created: Modification, modified: Modification,
                              mmlId: Option[Long], mValue: Option[Double],
                              propertyData: Seq[Property]) extends FloatingStop with RoadLinkStop

trait MassTransitStopService {
  def withDynSession[T](f: => T): T
  def roadLinkService: RoadLinkService
  def withDynTransaction[T](f: => T): T
  def eventbus: DigiroadEventBus

  case class PersistedMassTransitStop(id: Long, nationalId: Long, mmlId: Long, stopTypes: Seq[Int],
                                      municipalityCode: Int, lon: Double, lat: Double, mValue: Double,
                                      validityDirection: Option[Int], bearing: Option[Int],
                                      validityPeriod: Option[String], floating: Boolean,
                                      created: Modification, modified: Modification,
                                      propertyData: Seq[Property])

  case class MassTransitStopRow(id: Long, externalId: Long, assetTypeId: Long, point: Option[Point], productionRoadLinkId: Option[Long], roadLinkId: Long, mmlId: Long, bearing: Option[Int],
                                validityDirection: Int, validFrom: Option[LocalDate], validTo: Option[LocalDate], property: PropertyRow,
                                created: Modification, modified: Modification, wgsPoint: Option[Point], lrmPosition: LRMPosition,
                                roadLinkType: AdministrativeClass = Unknown, municipalityCode: Int, persistedFloating: Boolean) extends IAssetRow

  def getFloatingStops(includedMunicipalities: Option[Set[Int]]): Map[String, Seq[Long]] = {
    withDynSession {
      val optionalMunicipalities = includedMunicipalities.map(_.mkString(","))
      val allFloatingAssetsQuery = """
        select a.external_id, m.name_fi
        from asset a
        join municipality m on a.municipality_code = m.id
        where asset_type_id = 10 and floating = '1'
      """

      val sql = optionalMunicipalities match {
        case Some(municipalities) => allFloatingAssetsQuery + s" and municipality_code in ($municipalities)"
        case _ => allFloatingAssetsQuery
      }

      StaticQuery.queryNA[(Long, String)](sql).list()
        .groupBy(_._2)
        .mapValues(_.map(_._1))
    }
  }

  def getByNationalId(nationalId: Long, municipalityValidation: Int => Unit): Option[MassTransitStopWithProperties] = {
    withDynTransaction {
      val persistedStop = getPersistedMassTransitStops(withNationalId(nationalId)).headOption
      persistedStop.map(_.municipalityCode).foreach(municipalityValidation)
      persistedStop.map(withFloatingUpdate(persistedStopToMassTransitStopWithProperties(fetchRoadLink)))
    }
  }

  private def withFloatingUpdate[T <: FloatingStop](toMassTransitStop: PersistedMassTransitStop => T)
                                (persistedStop: PersistedMassTransitStop): T = {
    val massTransitStop = toMassTransitStop(persistedStop)
    if (persistedStop.floating != massTransitStop.floating) updateFloating(massTransitStop.id, massTransitStop.floating)
    massTransitStop
  }

  private def isFloating(persistedStop: PersistedMassTransitStop, roadLink: Option[(Int, Seq[Point])]): Boolean = {
    val point = Point(persistedStop.lon, persistedStop.lat)
    roadLink match {
      case None => true
      case Some((municipalityCode, geometry)) => municipalityCode != persistedStop.municipalityCode ||
        !coordinatesWithinThreshold(Some(point), GeometryUtils.calculatePointFromLinearReference(geometry, persistedStop.mValue))
    }
  }

  private def persistedStopToMassTransitStopWithProperties(roadLinkByMmlId: Long => Option[(Int, Seq[Point])])
                                                          (persistedStop: PersistedMassTransitStop): MassTransitStopWithProperties = {
    val floating = isFloating(persistedStop, roadLinkByMmlId(persistedStop.mmlId))
    MassTransitStopWithProperties(id = persistedStop.id, nationalId = persistedStop.nationalId, stopTypes = persistedStop.stopTypes,
      lon = persistedStop.lon, lat = persistedStop.lat, validityDirection = persistedStop.validityDirection,
      bearing = persistedStop.bearing, validityPeriod = persistedStop.validityPeriod, floating = floating,
      propertyData = persistedStop.propertyData)
  }

  private def convertPersistedStop[T](conversion: (PersistedMassTransitStop, Boolean) => T,
                                      roadLinkByMmlId: Long => Option[(Int, Seq[Point])])
                                     (persistedStop: PersistedMassTransitStop): T = {
    val floating = isFloating(persistedStop, roadLinkByMmlId(persistedStop.mmlId))
    conversion(persistedStop, floating)
  }

  private def getPersistedMassTransitStops(queryFilter: String => String): Seq[PersistedMassTransitStop] = {
    val query = """
        select a.id, a.external_id, a.asset_type_id, a.bearing, lrm.side_code,
        a.valid_from, a.valid_to, geometry, a.municipality_code, a.floating,
        p.id, p.public_id, p.property_type, p.ui_position_index, p.required, e.value,
        case
          when e.name_fi is not null then e.name_fi
          when tp.value_fi is not null then tp.value_fi
          else null
        end as display_value,
        lrm.id, lrm.start_measure, lrm.end_measure, lrm.prod_road_link_id, lrm.road_link_id, lrm.mml_id,
        a.created_date, a.created_by, a.modified_date, a.modified_by,
        SDO_CS.TRANSFORM(a.geometry, 4326) AS position_wgs84
        from asset a
          join asset_link al on a.id = al.asset_id
          join lrm_position lrm on al.position_id = lrm.id
        join property p on a.asset_type_id = p.asset_type_id
          left join single_choice_value s on s.asset_id = a.id and s.property_id = p.id and p.property_type = 'single_choice'
          left join text_property_value tp on tp.asset_id = a.id and tp.property_id = p.id and (p.property_type = 'text' or p.property_type = 'long_text')
          left join multiple_choice_value mc on mc.asset_id = a.id and mc.property_id = p.id and p.property_type = 'multiple_choice'
          left join enumerated_value e on mc.enumerated_value_id = e.id or s.enumerated_value_id = e.id
      """
    queryToPersistedMassTransitStops(queryFilter(query))
  }

  private def queryToPersistedMassTransitStops(query: String): Seq[PersistedMassTransitStop] = {
    val rows = StaticQuery.queryNA[MassTransitStopRow](query).iterator().toSeq

    rows.groupBy(_.id).map { case (id, stopRows) =>
      val row = stopRows.head
      val commonProperties: Seq[Property] = AssetPropertyConfiguration.assetRowToCommonProperties(row)
      val properties: Seq[Property] = commonProperties ++ OracleSpatialAssetDao.assetRowToProperty(stopRows)
      val point = row.point.get
      val validityPeriod = Some(constructValidityPeriod(row.validFrom, row.validTo))
      val stopTypes = extractStopTypes(stopRows)
      val mValue = row.lrmPosition.startMeasure

      id -> PersistedMassTransitStop(id = row.id, nationalId = row.externalId, mmlId = row.mmlId, stopTypes = stopTypes,
        municipalityCode = row.municipalityCode, lon = point.x, lat = point.y, mValue = mValue,
        validityDirection = Some(row.validityDirection), bearing = row.bearing,
        validityPeriod = validityPeriod, floating = row.persistedFloating, created = row.created, modified = row.modified,
        propertyData = properties)
    }.values.toSeq
  }

  private def withNationalId(nationalId: Long)(query: String): String = {
    query + s" where a.external_id = $nationalId"
  }

  private def withId(id: Long)(query: String): String = {
    query + s" where a.id = $id"
  }

  private def withFilter(filter: String)(query: String): String = {
    query + " " + filter
  }

  private def withMunicipality(municipalityCode: Int)(query: String): String = {
    withFilter(s"where a.asset_type_id = 10 and a.municipality_code = $municipalityCode")(query)
  }

  private def extractStopTypes(rows: Seq[MassTransitStopRow]): Seq[Int] = {
    rows
      .filter { row => row.property.publicId.equals("pysakin_tyyppi") }
      .filterNot { row => row.property.propertyValue.isEmpty }
      .map { row => row.property.propertyValue.toInt }
  }

  private def eventBusMassTransitStop(stop: PersistedMassTransitStop, municipalityName: String) = {
    EventBusMassTransitStop(municipalityNumber = stop.municipalityCode, municipalityName = municipalityName,
      nationalId = stop.nationalId, lon = stop.lon, lat = stop.lat, bearing = stop.bearing,
      validityDirection = stop.validityDirection, created = stop.created, modified = stop.modified,
      propertyData = stop.propertyData)
  }

  def updateExisting(id: Long, optionalPosition: Option[Position], properties: Seq[SimpleProperty], username: String, municipalityValidation: Int => Unit): MassTransitStopWithProperties = {
    withDynTransaction {
      val persistedStop = getPersistedMassTransitStops(withId(id)).headOption
      persistedStop.map(_.municipalityCode).foreach(municipalityValidation)
      val mmlId = optionalPosition match {
        case Some(position) => position.roadLinkId
        case _ => persistedStop.get.mmlId
      }
      val (municipalityCode, geometry) = fetchRoadLink(mmlId).getOrElse(throw new NoSuchElementException)
      OracleSpatialAssetDao.updateAssetLastModified(id, username)
      if (properties.nonEmpty) {
        OracleSpatialAssetDao.updateAssetProperties(id, properties)
      }
      if (optionalPosition.isDefined) {
        val position = optionalPosition.get
        val point = Point(position.lon, position.lat)
        val mValue = calculateLinearReferenceFromPoint(point, geometry)
        updateLrmPosition(id, mValue, mmlId)
        updateBearing(id, position)
        updateMunicipality(id, municipalityCode)
        updateAssetGeometry(id, point)
      }
      val updatedStop = getPersistedMassTransitStops(withId(id)).headOption
      updatedStop.foreach { persistedStop =>
        val municipalityName = OracleSpatialAssetDao.getMunicipalityNameByCode(persistedStop.municipalityCode)
        eventbus.publish("asset:saved", eventBusMassTransitStop(persistedStop, municipalityName))
      }
      updatedStop
        .map(withFloatingUpdate(persistedStopToMassTransitStopWithProperties({_ => Some((municipalityCode, geometry))})))
        .get
    }
  }

  private def fetchRoadLink(mmlId: Long): Option[(Int, Seq[Point])] = {
    roadLinkService.fetchVVHRoadlink(mmlId).map{ x => (x._1, x._2) }
  }

  def createNew(lon: Double, lat: Double, mmlId: Long, bearing: Int, username: String, properties: Seq[SimpleProperty]): MassTransitStopWithProperties = {
    val point = Point(lon, lat)
    val (municipalityCode, geometry) = fetchRoadLink(mmlId).getOrElse(throw new NoSuchElementException)
    val mValue = calculateLinearReferenceFromPoint(point, geometry)

    withDynTransaction {
      val assetId = Sequences.nextPrimaryKeySeqValue
      val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
      val nationalId = OracleSpatialAssetDao.getNationalBusStopId
      val floating = !coordinatesWithinThreshold(Some(point), GeometryUtils.calculatePointFromLinearReference(geometry, mValue))
      insertLrmPosition(lrmPositionId, mValue, mmlId)
      insertAsset(assetId, nationalId, lon, lat, bearing, username, municipalityCode, floating)
      insertAssetLink(assetId, lrmPositionId)
      val defaultValues = OracleSpatialAssetDao.propertyDefaultValues(10).filterNot(defaultValue => properties.exists(_.publicId == defaultValue.publicId))
      OracleSpatialAssetDao.updateAssetProperties(assetId, properties ++ defaultValues)
      getPersistedMassTransitStops(withId(assetId)).headOption
        .map(persistedStopToMassTransitStopWithProperties({_ => Some((municipalityCode, geometry))}))
        .get
    }
  }

  def getByBoundingBox(user: User, bounds: BoundingRectangle): Seq[MassTransitStop] = {
    case class MassTransitStopBeforeUpdate(stop: MassTransitStop, persistedFloating: Boolean)

    val roadLinks = roadLinkService.fetchVVHRoadlinks(bounds)
    withDynSession {
      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(bounds, "a.geometry")
      val filter = s"where a.asset_type_id = 10 and $boundingBoxFilter"
      val persistedMassTransitStops: Seq[PersistedMassTransitStop] = getPersistedMassTransitStops(withFilter(filter))

      val stopsBeforeUpdate = persistedMassTransitStops.filter { persistedStop =>
        user.isAuthorizedToRead(persistedStop.municipalityCode)
      }.map { persistedStop =>
        val floating = isFloating(persistedStop, roadLinks.find(_._1 == persistedStop.mmlId).map(link => (link._2, link._3)))
        MassTransitStopBeforeUpdate(MassTransitStop(persistedStop.id, persistedStop.nationalId,
          persistedStop.lon, persistedStop.lat, persistedStop.bearing, persistedStop.validityDirection.get,
          persistedStop.municipalityCode, persistedStop.validityPeriod.get, floating, persistedStop.stopTypes), persistedStop.floating)
      }

      stopsBeforeUpdate.foreach { stop =>
        if (stop.stop.floating != stop.persistedFloating) {
          updateFloating(stop.stop.id, stop.stop.floating)
        }
      }

      stopsBeforeUpdate.map(_.stop)
    }
  }

  def getByMunicipality(municipalityCode: Int): Seq[MassTransitStopWithTimeStamps] = {
    def toMassTransitStopWithTimeStamps(persistedStop: PersistedMassTransitStop, floating: Boolean): MassTransitStopWithTimeStamps = {
      MassTransitStopWithTimeStamps(id = persistedStop.id, nationalId = persistedStop.nationalId,
        lon = persistedStop.lon, lat = persistedStop.lat,
        bearing = persistedStop.bearing, floating = floating,
        created = persistedStop.created, modified = persistedStop.modified,
        mmlId = Some(persistedStop.mmlId), mValue = Some(persistedStop.mValue),
        propertyData = persistedStop.propertyData)
    }
    val roadLinks = roadLinkService.fetchVVHRoadlinks(municipalityCode)
    def findRoadlink(mmlId: Long): Option[(Int, Seq[Point])] = {
      val roadLink: Option[(Long, Int, Seq[Point], AdministrativeClass, TrafficDirection)] = roadLinks.find(_._1 == mmlId)
      roadLink.map(x => (x._2, x._3))
    }
    withDynSession {
      getPersistedMassTransitStops(withMunicipality(municipalityCode))
        .map(withFloatingUpdate(convertPersistedStop(toMassTransitStopWithTimeStamps, findRoadlink)))
        .toList
    }
  }

  def mandatoryProperties(): Map[String, String] = {
    val requiredProperties = withDynSession {
      sql"""select public_id, property_type from property where asset_type_id = 10 and required = 1""".as[(String, String)].iterator().toMap
    }
    val validityDirection = AssetPropertyConfiguration.commonAssetProperties(AssetPropertyConfiguration.ValidityDirectionId)
    requiredProperties + (validityDirection.publicId -> validityDirection.propertyType)
  }

  def calculateLinearReferenceFromPoint(point: Point, points: Seq[Point]): Double = {
    case class Projection(distance: Double, segmentIndex: Int, segmentLength: Double, mValue: Double)
    val lineSegments: Seq[((Point, Point), Int)] = points.zip(points.tail).zipWithIndex
    val projections: Seq[Projection] = lineSegments.map { case((p1: Point, p2: Point), segmentIndex: Int) =>
      val segmentLength = (p2 - p1).length()
      val directionVector = (p2 - p1).normalize()
      val negativeMValue = (p1 - point).dot(directionVector)
      val clampedNegativeMValue =
        if (negativeMValue > 0) 0
        else if (negativeMValue < (-1 * segmentLength)) -1 * segmentLength
        else negativeMValue
      val projectionVectorOnLineSegment: Vector3d = directionVector.scale(clampedNegativeMValue)
      val pointToLineSegment: Vector3d = (p1 - point) - projectionVectorOnLineSegment
      Projection(
        distance = pointToLineSegment.length(),
        segmentIndex = segmentIndex,
        segmentLength = segmentLength,
        mValue = -1 * clampedNegativeMValue)
    }
    val targetIndex = projections.sortBy(_.distance).head.segmentIndex
    val distanceBeforeTarget = projections.take(targetIndex).map(_.segmentLength).sum
    distanceBeforeTarget + projections(targetIndex).mValue
  }

  implicit val getMassTransitStopRow = new GetResult[MassTransitStopRow] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong
      val externalId = r.nextLong
      val assetTypeId = r.nextLong
      val bearing = r.nextIntOption
      val validityDirection = r.nextInt
      val validFrom = r.nextDateOption.map(new LocalDate(_))
      val validTo = r.nextDateOption.map(new LocalDate(_))
      val point = r.nextBytesOption.map(bytesToPoint)
      val municipalityCode = r.nextInt()
      val persistedFloating = r.nextBoolean()
      val propertyId = r.nextLong
      val propertyPublicId = r.nextString
      val propertyType = r.nextString
      val propertyUiIndex = r.nextInt
      val propertyRequired = r.nextBoolean
      val propertyValue = r.nextLongOption()
      val propertyDisplayValue = r.nextStringOption()
      val property = new PropertyRow(propertyId, propertyPublicId, propertyType, propertyUiIndex, propertyRequired, propertyValue.getOrElse(propertyDisplayValue.getOrElse("")).toString, propertyDisplayValue.getOrElse(null))
      val lrmId = r.nextLong
      val startMeasure = r.nextDouble()
      val endMeasure = r.nextDouble()
      val productionRoadLinkId = r.nextLongOption()
      val roadLinkId = r.nextLong
      val mmlId = r.nextLong
      val created = new Modification(r.nextTimestampOption().map(new DateTime(_)), r.nextStringOption)
      val modified = new Modification(r.nextTimestampOption().map(new DateTime(_)), r.nextStringOption)
      val wgsPoint = r.nextBytesOption.map(bytesToPoint)
      MassTransitStopRow(id, externalId, assetTypeId, point, productionRoadLinkId, roadLinkId, mmlId, bearing, validityDirection,
        validFrom, validTo, property, created, modified, wgsPoint,
        lrmPosition = LRMPosition(lrmId, startMeasure, endMeasure, point), municipalityCode = municipalityCode, persistedFloating = persistedFloating)
    }
  }

  private implicit val getLocalDate = new GetResult[Option[LocalDate]] {
    def apply(r: PositionedResult) = {
      r.nextDateOption().map(new LocalDate(_))
    }
  }

  private def constructValidityPeriod(validFrom: Option[LocalDate], validTo: Option[LocalDate]): String = {
    (validFrom, validTo) match {
      case (Some(from), None) => if (from.isAfter(LocalDate.now())) { ValidityPeriod.Future } else { ValidityPeriod.Current }
      case (None, Some(to)) => if (LocalDate.now().isAfter(to)) { ValidityPeriod.Past } else { ValidityPeriod.Current }
      case (Some(from), Some(to)) =>
        val interval = new Interval(from.toDateMidnight, to.toDateMidnight)
        if (interval.containsNow()) { ValidityPeriod.Current }
        else if (interval.isBeforeNow) { ValidityPeriod.Past }
        else { ValidityPeriod.Future }
      case _ => ValidityPeriod.Current
    }
  }

  private val FLOAT_THRESHOLD_IN_METERS = 3

  private def coordinatesWithinThreshold(pt1: Option[Point], pt2: Option[Point]): Boolean = {
    (pt1, pt2) match {
      case (Some(point1), Some(point2)) => point1.distanceTo(point2) <= FLOAT_THRESHOLD_IN_METERS
      case _ => false
    }
  }

  private def updateLrmPosition(id: Long, mValue: Double, mmlId: Long) {
    sqlu"""
           update lrm_position
           set start_measure = $mValue, end_measure = $mValue, mml_id = $mmlId
           where id = (
            select lrm.id
            from asset a
            join asset_link al on al.asset_id = a.id
            join lrm_position lrm on lrm.id = al.position_id
            where a.id = $id)
      """.execute
  }

  private def insertLrmPosition(id: Long, mValue: Double, mmlId: Long) {
    sqlu"""
           insert into lrm_position (id, start_measure, end_measure, mml_id)
           values ($id, $mValue, $mValue, $mmlId)
      """.execute
  }

  private def insertAsset(id: Long, nationalId: Long, lon: Double, lat: Double, bearing: Int, creator: String, municipalityCode: Int, floating: Boolean): Unit = {
    sqlu"""
           insert into asset (id, external_id, asset_type_id, bearing, created_by, municipality_code, geometry, floating)
           values ($id, $nationalId, 10, $bearing, $creator, $municipalityCode,
           MDSYS.SDO_GEOMETRY(4401, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,1,1), MDSYS.SDO_ORDINATE_ARRAY($lon, $lat, 0, 0)),
           $floating)
      """.execute
  }

  private def insertAssetLink(assetId: Long, lrmPositionId: Long): Unit = {

    sqlu"""
           insert into asset_link(asset_id, position_id)
           values ($assetId, $lrmPositionId)
      """.execute
  }

  private def updateBearing(id: Long, position: Position) {
    position.bearing.foreach { bearing =>
      sqlu"""
           update asset
           set bearing = $bearing
           where id = $id
        """.execute
    }
  }

  private def updateMunicipality(id: Long, municipalityCode: Int) {
    sqlu"""
           update asset
           set municipality_code = $municipalityCode
           where id = $id
      """.execute
  }

  private def updateFloating(id: Long, floating: Boolean) = sqlu"""update asset set floating = $floating where id = $id""".execute()
}

