package fi.liikennevirasto.digiroad2.util

import java.sql.SQLIntegrityConstraintViolationException
import java.util.Properties

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, BothDirections, TowardsDigitizing}
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.Asset.DateTimePropertyFormat
import fi.liikennevirasto.digiroad2.asset.{PointAssetValue, _}
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.linearasset.OracleLinearAssetDao
import fi.liikennevirasto.digiroad2.dao.pointasset.PersistedTrafficSign
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller.ChangeSet
import fi.liikennevirasto.digiroad2.linearasset.{Value, _}
import fi.liikennevirasto.digiroad2.middleware.TrafficSignManager
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.{AdditionalInformation, RoadLinkService}
import fi.liikennevirasto.digiroad2.service.linearasset._
import fi.liikennevirasto.digiroad2.service.pointasset.TrafficSignService
import fi.liikennevirasto.digiroad2.user.UserProvider
import org.joda.time.DateTime
import org.json4s
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, Formats, JInt, JObject, JString}
import org.json4s.jackson.Json

case class TrafficSignToLinear(roadLink: RoadLink, value: Value, sideCode: SideCode, startMeasure: Double, endMeasure: Double, signId: Set[Long], oldAssetId: Option[Long] = None)

trait TrafficSignLinearGenerator {
  def roadLinkService: RoadLinkService

  def vvhClient: VVHClient

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  //  type AssetValue <: Value
  val assetType: Int
  case object TrafficSignSerializer extends CustomSerializer[TrafficSignProperty](format =>
    ({
      case jsonObj: JObject =>
        val id = (jsonObj \ "id").extract[Long]
        val publicId = (jsonObj \ "publicId").extract[String]
        val propertyType = (jsonObj \ "propertyType").extract[String]
        val values: Seq[PointAssetValue] = (jsonObj \ "values").extractOpt[Seq[TextPropertyValue]].getOrElse((jsonObj \ "values").extractOpt[Seq[AdditionalPanel]].getOrElse(Seq()))
        val required = (jsonObj \ "required").extract[Boolean]
        val numCharacterMax = (jsonObj \ "numCharacterMax").extractOpt[Int]

        TrafficSignProperty(id, publicId, propertyType, required, values, numCharacterMax)
    },
      {
        case tv : TrafficSignProperty =>
          Extraction.decompose(tv)
      }))

  case object LinkGeomSourceSerializer extends CustomSerializer[LinkGeomSource](format => ({
    case JInt(lg) => LinkGeomSource.apply(lg.toInt)
  }, {
    case lg: LinkGeomSource => JInt(lg.value)
  }))

  protected implicit val jsonFormats: Formats = DefaultFormats + TrafficSignSerializer + LinkGeomSourceSerializer

  final val userCreate = "automatic_trafficSign_created"
  final val userUpdate = "automatic_trafficSign_updated"
  final val debbuger = true

  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/bonecp.properties"))
    props
  }

  lazy val dr2properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  lazy val userProvider: UserProvider = {
    Class.forName(dr2properties.getProperty("digiroad2.userProvider")).newInstance().asInstanceOf[UserProvider]
  }

  lazy val eventbus: DigiroadEventBus = {
    new DigiroadEventBus
  }

  lazy val linearAssetService: LinearAssetService = {
    new LinearAssetService(roadLinkService, new DummyEventBus)
  }

  lazy val manoeuvreService: ManoeuvreService = {
    new ManoeuvreService(roadLinkService, new DummyEventBus)
  }

  lazy val trafficSignService: TrafficSignService = {
    new TrafficSignService(roadLinkService, userProvider, eventbus)
  }

  lazy val oracleLinearAssetDao: OracleLinearAssetDao = new OracleLinearAssetDao(roadLinkService.vvhClient, roadLinkService)

  def createValue(trafficSigns: Seq[PersistedTrafficSign]): Option[Value]

  def getExistingSegments(roadLinks: Seq[RoadLink]): Seq[PersistedLinearAsset]

  def signBelongTo(trafficSign: PersistedTrafficSign): Boolean

  def updateLinearAsset(oldAssetId: Long, newValue: Value, username: String): Seq[Long]

  def fetchTrafficSignRelatedAssets(trafficSignId: Long, withTransaction: Boolean = false): Seq[PersistedLinearAsset]

  def assetToUpdate(assets: Seq[PersistedLinearAsset], trafficSign: PersistedTrafficSign, createdValue: Value, username: String): Unit

  def createLinearAsset(newSegment: TrafficSignToLinear, username: String): Long

  def mappingValue(segment: Seq[TrafficSignToLinear]): Value

  def compareValue(value1: Value, value2: Value): Boolean

  def withdraw(value1: Value, value2: Value): Value

  def getPointOfInterest(first: Point, last: Point, sideCode: SideCode): (Option[Point], Option[Point], Option[Int]) = {
    sideCode match {
      case SideCode.TowardsDigitizing => (None, Some(last), Some(sideCode.value))
      case SideCode.AgainstDigitizing => (Some(first), None, Some(sideCode.value))
      case _ => (Some(first), Some(last), Some(sideCode.value))
    }
  }

  def createValidPeriod(trafficSignType: TrafficSignType, additionalPanel: AdditionalPanel): Set[ValidityPeriod] = {
    TimePeriodClass.fromTrafficSign(trafficSignType).filterNot(_ == TimePeriodClass.Unknown).flatMap { period =>
      val regexMatch = "[(]?\\d+\\s*[-]{1}\\s*\\d+[)]?".r
      val validPeriodsCount = regexMatch.findAllIn(additionalPanel.panelInfo)
      val validPeriods = regexMatch.findAllMatchIn(additionalPanel.panelInfo)

      if (validPeriodsCount.length == 3 && ValidityPeriodDayOfWeek.fromTimeDomainValue(period.value) == ValidityPeriodDayOfWeek.Sunday) {
        val convertPeriod = Map(0 -> ValidityPeriodDayOfWeek.Weekday, 1 -> ValidityPeriodDayOfWeek.Saturday, 2 -> ValidityPeriodDayOfWeek.Sunday)
        validPeriods.zipWithIndex.map { case (timePeriod, index) =>
          val splitTime = timePeriod.toString.replaceAll("[\\(\\)]|\\s", "").split("-")
          ValidityPeriod(splitTime.head.toInt, splitTime.last.toInt, convertPeriod(index))
        }.toSet

      } else
        validPeriods.map { timePeriod =>
          val splitTime = timePeriod.toString.replaceAll("[\\(\\)]|\\s", "").split("-")
          ValidityPeriod(splitTime.head.toInt, splitTime.last.toInt, ValidityPeriodDayOfWeek.fromTimeDomainValue(period.value))
        }
    }
  }

  def segmentsManager(roadLinks: Seq[RoadLink], trafficSigns: Seq[PersistedTrafficSign], existingSegments: Seq[TrafficSignToLinear]): Set[TrafficSignToLinear] = {
    if (debbuger) println(s"segmentsManager : roadLinkSize = ${roadLinks.size}")
    val startEndRoadLinks = findStartEndRoadLinkOnChain(roadLinks)

    val newSegments = startEndRoadLinks.flatMap { case (roadLink, startPointOfInterest, lastPointOfInterest) =>
      baseProcess(trafficSigns, roadLinks, roadLink, (startPointOfInterest, lastPointOfInterest, None), Seq())
    }.distinct

    val groupedAssets = (newSegments ++ existingSegments).groupBy(_.roadLink)
    val assets = fillTopology(roadLinks, groupedAssets)

    convertEndRoadSegments(assets, startEndRoadLinks).toSet
  }

  def fillTopology(topology: Seq[RoadLink], linearAssets: Map[RoadLink, Seq[TrafficSignToLinear]]): Seq[TrafficSignToLinear] = {
    if (debbuger) println("fillTopology")
    val fillOperations: Seq[Seq[TrafficSignToLinear] => Seq[TrafficSignToLinear]] = Seq(
      combine,
      convertOneSideCode
    )

    topology.foldLeft(Seq.empty[TrafficSignToLinear]) { case (existingAssets, roadLink) =>
      val assetsOnRoadLink = linearAssets.getOrElse(roadLink, Nil)
      val adjustedAssets = fillOperations.foldLeft(assetsOnRoadLink) { case (currentSegments, operation) =>
        operation(currentSegments)
      }
      existingAssets ++ adjustedAssets
    }
  }

  def findStartEndRoadLinkOnChain(roadLinks: Seq[RoadLink]): Seq[(RoadLink, Option[Point], Option[Point])] = {
    if (debbuger) println("findStartEndRoadLinkOnChain")
    val borderRoadLinks = roadLinks.filterNot { r =>
      val (first, last) = GeometryUtils.geometryEndpoints(r.geometry)
      val RoadLinksFiltered = roadLinks.filterNot(_.linkId == r.linkId)

      RoadLinksFiltered.exists { r3 =>
        val (first2, last2) = GeometryUtils.geometryEndpoints(r3.geometry)
        GeometryUtils.areAdjacent(first, first2) || GeometryUtils.areAdjacent(first, last2)
      } &&
        RoadLinksFiltered.exists { r3 =>
          val (first2, last2) = GeometryUtils.geometryEndpoints(r3.geometry)
          GeometryUtils.areAdjacent(last, first2) || GeometryUtils.areAdjacent(last, last2)
        }
    }

    borderRoadLinks.map { roadLink =>
      val (first, last) = GeometryUtils.geometryEndpoints(roadLink.geometry)
      val isStart = roadLinks.diff(borderRoadLinks).exists { r3 =>
        val (first2, last2) = GeometryUtils.geometryEndpoints(r3.geometry)
        GeometryUtils.areAdjacent(first, first2) || GeometryUtils.areAdjacent(first, last2)
      }

      if (isStart) {
        (roadLink, Some(first), None)
      } else
        (roadLink, None, Some(last))
    }
  }

  def segmentsConverter(existingAssets: Seq[PersistedLinearAsset], roadLinks: Seq[RoadLink]): (Seq[TrafficSignToLinear], Seq[TrafficSignToLinear]) = {
    if (debbuger) println("segmentsConverter")
    val connectedTrafficSignIds =
      if (existingAssets.nonEmpty)
        oracleLinearAssetDao.getConnectedAssetFromLinearAsset(existingAssets.map(_.id))
      else
        Seq()

    val signIdsGroupedByAssetId = connectedTrafficSignIds.groupBy(_._1)
    val trafficSigns = if (connectedTrafficSignIds.nonEmpty)
      oracleLinearAssetDao.getTrafficSignsToProcessById(connectedTrafficSignIds.map(_._2))
    else Seq()

    val existingWithoutSignsRelation = existingAssets.filter(_.value.isDefined).flatMap { asset =>
      val relevantSigns = trafficSigns.filter(sign => signIdsGroupedByAssetId(asset.id).map(_._2).contains(sign._1))

      val persistedTrafficSign = relevantSigns.map{case (id, value) => Json(jsonFormats).read[PersistedTrafficSign](value)}
      val createdValue = createValue(persistedTrafficSign)
      if (createdValue.isEmpty)
        Some(TrafficSignToLinear(roadLinks.find(_.linkId == asset.linkId).get, asset.value.get, SideCode.apply(asset.sideCode), asset.startMeasure, asset.endMeasure, Set(), None))
      else if (!compareValue(asset.value.get, createdValue.get))
        Some(TrafficSignToLinear(roadLinks.find(_.linkId == asset.linkId).get, withdraw(asset.value.get, createdValue.get), SideCode.apply(asset.sideCode), asset.startMeasure, asset.endMeasure, Set(), None))
      else
        None
    }

    val allExistingSegments = existingAssets.filter(_.value.isDefined).map { asset =>
      val trafficSignIds = connectedTrafficSignIds.filter(_._1 == asset.id).map(_._2).toSet
      TrafficSignToLinear(roadLinks.find(_.linkId == asset.linkId).get, asset.value.get, SideCode.apply(asset.sideCode), asset.startMeasure, asset.endMeasure, trafficSignIds, Some(asset.id))
    }

    (existingWithoutSignsRelation, allExistingSegments)
  }

  def baseProcess(trafficSigns: Seq[PersistedTrafficSign], roadLinks: Seq[RoadLink], actualRoadLink: RoadLink, previousInfo: (Option[Point], Option[Point], Option[Int]), result: Seq[TrafficSignToLinear]): Set[TrafficSignToLinear] = {
    if (debbuger) println("baseProcess")
    val filteredRoadLinks = roadLinks.filterNot(_.linkId == actualRoadLink.linkId)
    val signsOnRoadLink = trafficSigns.filter(_.linkId == actualRoadLink.linkId)
    (if (signsOnRoadLink.nonEmpty) {
      signsOnRoadLink.flatMap { sign =>
        val (first, last) = GeometryUtils.geometryEndpoints(actualRoadLink.geometry)
        val pointOfInterest = getPointOfInterest(first, last, SideCode(sign.validityDirection))
        createSegmentPieces(actualRoadLink, filteredRoadLinks, sign, trafficSigns, pointOfInterest, result).toSeq ++
          getAdjacents(pointOfInterest, filteredRoadLinks).flatMap { case (roadLink, nextPoint) =>
            baseProcess(trafficSigns, filteredRoadLinks, roadLink, nextPoint, result)
          }
      }
    } else {
      getAdjacents(previousInfo, filteredRoadLinks).flatMap { case (roadLink, nextPoint) =>
        baseProcess(trafficSigns, filteredRoadLinks, roadLink, nextPoint, result)
      }
    }).toSet
  }

  def createSegmentPieces(actualRoadLink: RoadLink, allRoadLinks: Seq[RoadLink], sign: PersistedTrafficSign, signs: Seq[PersistedTrafficSign], pointOfInterest: (Option[Point], Option[Point], Option[Int]), result: Seq[TrafficSignToLinear]): Set[TrafficSignToLinear] = {
    if (debbuger) println("createSegmentPieces")
    createValue(Seq(sign)) match {
      case Some(value) =>
        val pairSign = getPairSign(actualRoadLink, sign, signs.filter(_.linkId == actualRoadLink.linkId), pointOfInterest._3.get)
        val generatedSegmentPieces = generateSegmentPieces(actualRoadLink, sign, value, pairSign, pointOfInterest._3.get)

        (if (pairSign.isEmpty) {
          val adjRoadLinks = getAdjacents(pointOfInterest, allRoadLinks.filterNot(_.linkId == actualRoadLink.linkId))
          if (adjRoadLinks.nonEmpty) {
            adjRoadLinks.flatMap { case (newRoadLink, (nextFirst, nextLast, nextDirection)) =>
              createSegmentPieces(newRoadLink, allRoadLinks.filterNot(_.linkId == newRoadLink.linkId), sign, signs, (nextFirst, nextLast, nextDirection), generatedSegmentPieces +: result)
            }
          } else
            generatedSegmentPieces +: result
        } else
          generatedSegmentPieces +: result).toSet
      case _ => Set()
    }
  }

  def generateSegmentPieces(currentRoadLink: RoadLink, sign: PersistedTrafficSign, value: Value, pairedSign: Option[PersistedTrafficSign], direction: Int): TrafficSignToLinear = {
    if (debbuger) println("generateSegmentPieces")
    pairedSign match {
      case Some(pair) =>
        if (pair.linkId == sign.linkId) {
          val orderedMValue = Seq(sign.mValue, pair.mValue).sorted

          TrafficSignToLinear(currentRoadLink, value, SideCode.apply(sign.validityDirection), orderedMValue.head, orderedMValue.last, Set(sign.id))
        } else {
          val (starMeasure, endMeasure) = if (SideCode.apply(direction) == TowardsDigitizing)
            (0.toDouble, pair.mValue)
          else {
            val length = GeometryUtils.geometryLength(currentRoadLink.geometry)
            (pair.mValue, length)
          }
          TrafficSignToLinear(currentRoadLink, value, SideCode.apply(direction), starMeasure, endMeasure, Set(sign.id))
        }
      case _ =>
        if (currentRoadLink.linkId == sign.linkId) {
          val (starMeasure, endMeasure) = if (SideCode.apply(direction) == AgainstDigitizing)
            (0L.toDouble, sign.mValue)
          else {
            val length = GeometryUtils.geometryLength(currentRoadLink.geometry)
            (sign.mValue, length)
          }

          TrafficSignToLinear(currentRoadLink, value, SideCode.apply(direction), starMeasure, endMeasure, Set(sign.id))
        }
        else {

          val length = GeometryUtils.geometryLength(currentRoadLink.geometry)
          TrafficSignToLinear(currentRoadLink, value, SideCode.apply(direction), 0, length, Set(sign.id))
        }
    }
  }

  def getPairSign(actualRoadLink: RoadLink, mainSign: PersistedTrafficSign, allSignsRelated: Seq[PersistedTrafficSign], direction: Int): Option[PersistedTrafficSign] = {
    if (debbuger) println("getPairSign")
    val mainSignType = trafficSignService.getProperty(mainSign, trafficSignService.typePublicId).get.propertyValue.toInt
    val mainAdditionalPanels = trafficSignService.getAllProperties(mainSign, trafficSignService.additionalPublicId).map(_.asInstanceOf[AdditionalPanel])

    allSignsRelated.filterNot(_.id == mainSign.id).filter(_.linkId == actualRoadLink.linkId).find { sign =>
      compareValue(createValue(Seq(mainSign)).get, createValue(Seq(sign)).get) && sign.validityDirection != direction
    }
  }

  def deleteOrUpdateAssetBasedOnSign(trafficSign: PersistedTrafficSign): Unit = {
    val username = "automatic_trafficSign_deleted"
    val trafficSignRelatedAssets = fetchTrafficSignRelatedAssets(trafficSign.id)
    val createdValue = createValue(Seq(trafficSign))

    val (toDelete, toUpdate) = trafficSignRelatedAssets.partition { asset =>
      if (createdValue.isEmpty) true else compareValue(asset.value.get, createdValue.get)
    }

    toDelete.foreach { asset =>
      linearAssetService.expireAsset(assetType, asset.id, username, true, false)
      oracleLinearAssetDao.expireConnectedByLinearAsset(asset.id)
    }

    assetToUpdate(toUpdate, trafficSign, createdValue.get, username)
  }

  def getAdjacents(previousInfo: (Option[Point], Option[Point], Option[Int]), roadLinks: Seq[RoadLink]): Seq[(RoadLink, (Option[Point], Option[Point], Option[Int]))] = {
    if (debbuger) println("getAdjacents")
    val (prevFirst, prevLast, direction) = previousInfo
    val filter = roadLinks.filter {
      roadLink =>
        GeometryUtils.areAdjacent(roadLink.geometry, prevFirst.getOrElse(prevLast.get))
    }

    filter.map { roadLink =>
      val (first, last) = GeometryUtils.geometryEndpoints(roadLink.geometry)
      val switchDirection = direction match {
        case Some(value) => Some(SideCode.switch(SideCode.apply(value)).value)
        case _ => None
      }
      val complementaryInfo: (Option[Point], Option[Point], Option[Int]) = (prevFirst, prevLast) match {
        case (Some(prevPoint), None) => if (GeometryUtils.areAdjacent(first, prevPoint)) (None, Some(last), switchDirection) else (Some(first), None, direction)
        case _ => if (GeometryUtils.areAdjacent(last, prevLast.get)) (Some(first), None, switchDirection) else (None, Some(last), direction)
      }
      (roadLink, complementaryInfo)
    }
  }

  def createLinearAssetAccordingSegmentsInfo(newSegment: TrafficSignToLinear, username: String): Unit = {
    if (debbuger) println("createLinearAssetAccordingSegmentsInfo")
    val newAssetId = createLinearAsset(newSegment, username)

    newSegment.signId.foreach { signId =>
      createAssetRelation(newAssetId, signId)
    }
  }

  protected def createAssetRelation(linearAssetId: Long, trafficSignId: Long): Unit = {
    if (debbuger) println("createAssetRelation")
    try {
      oracleLinearAssetDao.insertConnectedAsset(linearAssetId, trafficSignId)
    } catch {
      case ex: SQLIntegrityConstraintViolationException => print("") //the key already exist with a valid date
      case e: Exception => print("SQL Exception ")
        throw new RuntimeException("SQL exception " + e.getMessage)
    }
  }

  def deleteLinearAssets(existingSeg: Seq[TrafficSignToLinear]): Unit = {
    if (debbuger) println(s"deleteLinearAssets ${existingSeg.size}")
    existingSeg.foreach { asset =>
      linearAssetService.expireAsset(assetType, asset.oldAssetId.get, userUpdate, true, false)
      oracleLinearAssetDao.expireConnectedByLinearAsset(asset.oldAssetId.get)
    }
  }

  def updateRelation(newSeg: TrafficSignToLinear, oldSeg: TrafficSignToLinear): Unit = {
    oldSeg.signId.diff(newSeg.signId).foreach(sign => oracleLinearAssetDao.expireConnectedByPointAsset(sign))
    newSeg.signId.diff(oldSeg.signId).foreach(sign => createAssetRelation(oldSeg.oldAssetId.get, sign))
  }

  def combine(segments: Seq[TrafficSignToLinear] /*, endRoadLinksInfo: Seq[(RoadLink, Option[Point], Option[Point])]*/): Seq[TrafficSignToLinear] = {
    def squash(startM: Double, endM: Double, segments: Seq[TrafficSignToLinear]): Seq[TrafficSignToLinear] = {
      val sl = segments.filter(sl => sl.startMeasure <= startM && sl.endMeasure >= endM)
      val a = sl.filter(sl => sl.sideCode.equals(SideCode.AgainstDigitizing) || sl.sideCode.equals(SideCode.BothDirections))
      val t = sl.filter(sl => sl.sideCode.equals(SideCode.TowardsDigitizing) || sl.sideCode.equals(SideCode.BothDirections))

      (a.headOption, t.headOption) match {
        case (Some(x), Some(y)) => Seq(TrafficSignToLinear(x.roadLink, mappingValue(a), AgainstDigitizing, startM, endM, x.signId, x.oldAssetId), TrafficSignToLinear(y.roadLink, mappingValue(t), TowardsDigitizing, startM, endM, y.signId, y.oldAssetId))
        case (Some(x), None) => Seq(TrafficSignToLinear(x.roadLink, mappingValue(a), AgainstDigitizing, startM, endM, x.signId, x.oldAssetId))
        case (None, Some(y)) => Seq(TrafficSignToLinear(y.roadLink, mappingValue(t), TowardsDigitizing, startM, endM, y.signId, y.oldAssetId))
        case _ => Seq()
      }
    }

    def combineEqualValues(segmentPieces: Seq[TrafficSignToLinear] /*, segments : Seq[TrafficSignToLinear]*/): Seq[TrafficSignToLinear] = {
      val seg1 = segmentPieces.head
      val seg2 = segmentPieces.last
      if (seg1.startMeasure.equals(seg2.startMeasure) && seg1.endMeasure.equals(seg2.endMeasure) && compareValue(seg1.value, seg2.value) && seg1.sideCode != seg2.sideCode) {
        val winnerSegment = if (seg1.oldAssetId.nonEmpty) seg1 else seg2
        Seq(winnerSegment.copy(sideCode = BothDirections, signId = seg1.signId ++ seg2.signId))
      } else
        segmentPieces
    }

    val pointsOfInterest = (segments.map(_.startMeasure) ++ segments.map(_.endMeasure)).distinct.sorted
    if (pointsOfInterest.length < 2)
      return segments
    val pieces = pointsOfInterest.zip(pointsOfInterest.tail)
    val segmentPieces = pieces.flatMap(p => squash(p._1, p._2, segments))
    segmentPieces.groupBy(_.startMeasure).flatMap(n => combineEqualValues(n._2)).toSeq
  }

  def findNextEndAssets(segments: Seq[TrafficSignToLinear], baseSegment: TrafficSignToLinear, result: Seq[TrafficSignToLinear] = Seq(), numberOfAdjacent: Int = 0): Seq[TrafficSignToLinear] = {
    val adjacent = roadLinkService.getAdjacentTemp(baseSegment.roadLink.linkId)
    val (start, _) = GeometryUtils.geometryEndpoints(baseSegment.roadLink.geometry)
    val allInSameSide = adjacent.forall { adj =>
      val (first, last) = GeometryUtils.geometryEndpoints(adj.geometry)
      GeometryUtils.areAdjacent(start, first) || GeometryUtils.areAdjacent(start, last)
    }
    if (numberOfAdjacent == 1 && adjacent.size > 1) {
      segments.filter(_ == baseSegment).map(_.copy(sideCode = SideCode.BothDirections)) ++ result
    } else {
      val newBaseSegments = segments.filterNot(_.roadLink == baseSegment.roadLink)
      val newResult = segments.filter(_.roadLink == baseSegment.roadLink).map(_.copy(sideCode = SideCode.BothDirections)) ++ result

      if ((adjacent.size == 1 || allInSameSide) && newBaseSegments.nonEmpty) {
        newBaseSegments.filter(newSeg => adjacent.map(_.linkId).contains(newSeg.roadLink.linkId)).flatMap{ baseSegment => findNextEndAssets(newBaseSegments, baseSegment, newResult, adjacent.size)}
      } else
        newResult
    }
  }

  def convertEndRoadSegments(segments: Seq[TrafficSignToLinear], endRoadLinksInfo: Seq[(RoadLink, Option[Point], Option[Point])]): Seq[TrafficSignToLinear] = {
    val segmentsOndEndRoads = segments.filter { seg =>
      endRoadLinksInfo.exists { case (endRoadLink, firstPoint, lastPoint) =>
        val (first, _) = GeometryUtils.geometryEndpoints(endRoadLink.geometry)
        //if is a lastRoaLink, the point of interest is the first point
        (if (GeometryUtils.areAdjacent(first, firstPoint.getOrElse(lastPoint.get))) {
          Math.abs(seg.startMeasure - 0) < 0.01
        } else {
          Math.abs(seg.endMeasure - GeometryUtils.geometryLength(endRoadLink.geometry)) < 0.01
        }) && seg.roadLink.linkId == endRoadLink.linkId
      }
    }

    val endSegments = segmentsOndEndRoads.flatMap { baseSegment =>
      findNextEndAssets(segments, baseSegment)
    }.distinct

    segments.filterNot(seg => endSegments.exists(endSeg => seg.startMeasure == endSeg.startMeasure && seg.endMeasure == endSeg.endMeasure && seg.roadLink.linkId == endSeg.roadLink.linkId)) ++ endSegments
  }

  def convertOneSideCode(segments: Seq[TrafficSignToLinear]): Seq[TrafficSignToLinear] = {
    segments.map { seg =>
      if (seg.roadLink.trafficDirection != TrafficDirection.BothDirections)
        seg.copy(sideCode = BothDirections)
      else
        seg
    }
  }

  def combineSegments(allSegments: Seq[TrafficSignToLinear]): Set[TrafficSignToLinear] = {
    val groupedSegments = allSegments.groupBy(_.roadLink)

    groupedSegments.keys.flatMap { RoadLink =>
      val sortedSegments = groupedSegments(RoadLink).sortBy(_.startMeasure)
      sortedSegments.tail.foldLeft(Seq(sortedSegments.head)) { case (result, row) =>
        if (Math.abs(result.last.endMeasure - row.startMeasure) < 0.001 && result.last.value.equals(row.value))
          result.last.copy(endMeasure = row.endMeasure) +: result.init
        else
          result :+ row
      }
    }.toSet
  }

  private def getAllRoadLinksWithSameName(signRoadLink: RoadLink): Seq[RoadLink] = {
    val tsRoadNameInfo =
      if (signRoadLink.attributes.get("ROADNAME_FI").exists(_.toString.trim.nonEmpty)) {
        Some("ROADNAME_FI", signRoadLink.attributes("ROADNAME_FI").toString)
      } else if (signRoadLink.attributes.get("ROADNAME_SE").exists(_.toString.trim.nonEmpty)) {
        Some("ROADNAME_FI", signRoadLink.attributes("ROADNAME_SE").toString)
      } else
        None

    //RoadLink with the same Finnish/Swedish name
    tsRoadNameInfo.map { case (roadNamePublicIds, roadNameSource) =>
      roadLinkService.getRoadLinksAndComplementaryByRoadNameFromVVH(roadNamePublicIds, Set(roadNameSource), false).filter(_.administrativeClass != State)
    }.getOrElse(Seq())
  }

  def isToUpdateRelation(newSeg: TrafficSignToLinear)(oldSeg: TrafficSignToLinear): Boolean = {
    oldSeg.roadLink.linkId == newSeg.roadLink.linkId && oldSeg.sideCode == newSeg.sideCode &&
      Math.abs(oldSeg.startMeasure - newSeg.startMeasure) < 0.01 && Math.abs(oldSeg.endMeasure - newSeg.endMeasure) < 0.01 &&
      compareValue(oldSeg.value, newSeg.value)
  }

  def isToUpdateValue(newSeg: TrafficSignToLinear)(oldSeg: TrafficSignToLinear): Boolean = {
    oldSeg.roadLink.linkId == newSeg.roadLink.linkId && oldSeg.sideCode == newSeg.sideCode &&
      Math.abs(oldSeg.startMeasure - newSeg.startMeasure) < 0.01 && Math.abs(oldSeg.endMeasure - newSeg.endMeasure) < 0.01
  }

  def applyChangesBySegments(allSegments: Set[TrafficSignToLinear], existingSegments: Seq[TrafficSignToLinear]) : Unit = {
    if (allSegments.isEmpty)
      deleteLinearAssets(existingSegments)
    else {
      val segment = allSegments.head

      val toUpdateRelation = existingSegments.filter(isToUpdateRelation(segment))
      if (toUpdateRelation.nonEmpty) {
        val head = toUpdateRelation.head

        updateRelation(segment, head)
        applyChangesBySegments(allSegments.filterNot(_ == segment), existingSegments.filterNot(_ == head))

      } else {
        val toUpdateValue = existingSegments.filter(isToUpdateValue(segment))
        if (toUpdateValue.nonEmpty) {
          val head = toUpdateValue.head

          updateLinearAsset(head.oldAssetId.get, segment.value, userUpdate)
          updateRelation(segment, head)

          applyChangesBySegments(allSegments.filterNot(_ == segment), existingSegments.filterNot(_ == head))
        } else {
          createLinearAssetAccordingSegmentsInfo(segment, userCreate)

          applyChangesBySegments(allSegments.filterNot(_ == segment), existingSegments)
        }
      }
    }
  }

  def iterativeProcess(roadLinks: Seq[RoadLink], processedRoadLinks: Seq[RoadLink]): Unit = {
    val roadLinkToBeProcessed = roadLinks.diff(processedRoadLinks)

    if (roadLinkToBeProcessed.nonEmpty) {
      val roadLink = roadLinkToBeProcessed.head
      println(s"Processing roadLink linkId ${roadLink.linkId}")
      val allRoadLinksWithSameName = withDynTransaction {
        val allRoadLinksWithSameName = getAllRoadLinksWithSameName(roadLink)
        if(allRoadLinksWithSameName.nonEmpty){
          val trafficSigns = trafficSignService.getTrafficSign(allRoadLinksWithSameName.map(_.linkId))
          val filteredTrafficSigns = trafficSigns.filter(signBelongTo)

          val existingAssets = getExistingSegments(allRoadLinksWithSameName)
          val (relevantExistingSegments , existingSegments) = segmentsConverter(existingAssets, allRoadLinksWithSameName)
          println(s"Processing: ${filteredTrafficSigns.size}")

          //create and Modify actions
          val allSegments = segmentsManager(allRoadLinksWithSameName, filteredTrafficSigns, relevantExistingSegments)
          applyChangesBySegments(allSegments, existingSegments)

          if (trafficSigns.nonEmpty)
            oracleLinearAssetDao.deleteTrafficSignsToProcess(trafficSigns.map(_.id), assetType)

          allRoadLinksWithSameName
        } else
          Seq()
      }
      iterativeProcess(roadLinkToBeProcessed.filterNot(_.linkId == roadLink.linkId), allRoadLinksWithSameName)
    }
  }

  protected def withFilter(filter: String)(query: String): String = {
    query + " " + filter
  }

  def createLinearAssetUsingTrafficSigns(): Unit = {
    println(s"Starting create ${AssetTypeInfo.apply(assetType).layerName} using traffic signs")
    println(DateTime.now())
    println("")

    val roadLinks = withDynTransaction {
      val trafficSignsToProcess = oracleLinearAssetDao.getTrafficSignsToProcess(assetType)

      val trafficSigns = if(trafficSignsToProcess.nonEmpty) trafficSignService.fetchPointAssetsWithExpired(withFilter(s"Where a.id in (${trafficSignsToProcess.mkString(",")}) ")) else Seq()
      val roadLinks = roadLinkService.getRoadLinksAndComplementaryByLinkIdsFromVVH(trafficSigns.map(_.linkId).toSet, false).filter(_.administrativeClass != State)
      val trafficSignsToTransform = trafficSigns.filter(asset => roadLinks.exists(_.linkId == asset.linkId))

      println(s"Total of trafficSign to process: ${trafficSigns.size}")
      val tsToDelete = trafficSigns.filter(_.expired)
      tsToDelete.foreach { ts =>
        // Delete actions
        deleteOrUpdateAssetBasedOnSign(ts)
      }

      //Remove the table sign added on State Road
      val trafficSignsToDelete = trafficSigns.diff(trafficSignsToTransform) ++ trafficSigns.filter(_.expired)
      if (trafficSignsToDelete.nonEmpty)
        oracleLinearAssetDao.deleteTrafficSignsToProcess(trafficSignsToDelete.map(_.id), assetType)

      roadLinks
    }
    println("Start processing traffic signs")
    iterativeProcess(roadLinks, Seq())

    println("")
    println("Complete at time: " + DateTime.now())
  }
}

//Prohibition
case class TrafficSignProhibitionGenerator(roadLinkServiceImpl: RoadLinkService) extends TrafficSignLinearGenerator  {
  override def roadLinkService: RoadLinkService = roadLinkServiceImpl
  override def vvhClient: VVHClient = roadLinkServiceImpl.vvhClient

  override val assetType : Int = Prohibition.typeId

  lazy val prohibitionService: ProhibitionService = {
    new ProhibitionService(roadLinkService, eventbus)
  }

  override def createValue(trafficSigns: Seq[PersistedTrafficSign]): Option[Prohibitions] = {
    if (debbuger) println("createValue")
    val value = trafficSigns.flatMap { trafficSign =>
      val signType = trafficSignService.getProperty(trafficSign, trafficSignService.typePublicId).get.propertyValue.toInt
      val additionalPanel = trafficSignService.getAllProperties(trafficSign, trafficSignService.additionalPublicId).map(_.asInstanceOf[AdditionalPanel])
      val types = ProhibitionClass.fromTrafficSign(TrafficSignType.applyOTHValue(signType))
      val additionalPanels = additionalPanel.sortBy(_.formPosition)

      val validityPeriods: Set[ValidityPeriod] =
        additionalPanels.flatMap { additionalPanel =>
          val trafficSignType = TrafficSignType.applyOTHValue(additionalPanel.panelType)
          createValidPeriod(trafficSignType, additionalPanel)
        }.toSet

        types.map(typeId => ProhibitionValue(typeId.value, validityPeriods, Set()))
    }
    if(value.nonEmpty) Some(Prohibitions(value)) else None
  }

  def fetchTrafficSignRelatedAssets(trafficSignId: Long, withTransaction: Boolean = false): Seq[PersistedLinearAsset] = {
    if (debbuger) println("fetchTrafficSignRelatedAssets")
    if (withTransaction) {
      withDynTransaction {
        val assetIds = oracleLinearAssetDao.getConnectedAssetFromTrafficSign(trafficSignId)
        oracleLinearAssetDao.fetchProhibitionsByIds(assetType, assetIds.toSet)
      }
    } else {
      val assetIds = oracleLinearAssetDao.getConnectedAssetFromTrafficSign(trafficSignId)
      oracleLinearAssetDao.fetchProhibitionsByIds(assetType, assetIds.toSet)
    }
  }

  override def getExistingSegments(roadLinks : Seq[RoadLink]): Seq[PersistedLinearAsset] = {
    if (debbuger) println("getExistingSegments")
    prohibitionService.getPersistedAssetsByLinkIds(assetType, roadLinks.map(_.linkId), false)
  }

  override def signBelongTo(trafficSign: PersistedTrafficSign): Boolean = {
    if (debbuger) println("signBelongTo")
    val signType = trafficSignService.getProperty(trafficSign, trafficSignService.typePublicId).get.propertyValue.toInt
    TrafficSignManager.belongsToProhibition(signType)
  }

  override def updateLinearAsset(oldAssetId: Long, newValue: Value, username: String): Seq[Long] = {
    if (debbuger) println("updateLinearAsset")
    prohibitionService.updateWithoutTransaction(Seq(oldAssetId), newValue, username)
  }

  override def createLinearAsset(newSegment: TrafficSignToLinear, username: String) : Long = {
    if (debbuger) println("createLinearAsset")
    prohibitionService.createWithoutTransaction(assetType, newSegment.roadLink.linkId, newSegment.value,
      newSegment.sideCode.value, Measures(newSegment.startMeasure, newSegment.endMeasure), username,
      vvhClient.roadLinkData.createVVHTimeStamp(), Some(newSegment.roadLink))
  }

  override def assetToUpdate(assets: Seq[PersistedLinearAsset], trafficSign: PersistedTrafficSign, createdValue: Value, username: String) : Unit = {
    if (debbuger) println("assetToUpdate")
    val groupedAssetsToUpdate = assets.map { asset =>
      (asset.id, asset.value.get.asInstanceOf[Prohibitions].prohibitions.diff(createdValue.asInstanceOf[Prohibitions].prohibitions))
    }.groupBy(_._2)

    groupedAssetsToUpdate.values.foreach { value =>
      prohibitionService.updateWithoutTransaction(value.map(_._1), Prohibitions(value.flatMap(_._2)), username)
      oracleLinearAssetDao.expireConnectedByPointAsset(trafficSign.id)
    }
  }

  override def mappingValue(segment: Seq[TrafficSignToLinear]): Prohibitions = {
    Prohibitions(segment.flatMap(_.value.asInstanceOf[Prohibitions].prohibitions).distinct)
  }

  override def compareValue(value1: Value, value2: Value) : Boolean = {
    value1.asInstanceOf[Prohibitions].equals(value2.asInstanceOf[Prohibitions])
  }

  override def withdraw(value1: Value, value2: Value): Value = {
     Prohibitions(value1.asInstanceOf[Prohibitions].prohibitions.diff(value2.asInstanceOf[Prohibitions].prohibitions))
  }
}

class TrafficSignHazmatTransportProhibitionGenerator(roadLinkServiceImpl: RoadLinkService) extends TrafficSignProhibitionGenerator(roadLinkServiceImpl: RoadLinkService)  {
  override def roadLinkService: RoadLinkService = roadLinkServiceImpl
  override val assetType : Int = HazmatTransportProhibition.typeId

  lazy val hazmatTransportProhibitionService: HazmatTransportProhibitionService = {
    new HazmatTransportProhibitionService(roadLinkService, eventbus)
  }

  override def createValue(trafficSigns: Seq[PersistedTrafficSign]): Option[Prohibitions] = {
    if (debbuger) println("createValue")
    val values = trafficSigns.flatMap{ trafficSign =>
    //val signType = trafficSignService.getProperty(trafficSign, trafficSignService.typePublicId).get.propertyValue.toInt
    val additionalPanels = trafficSignService.getAllProperties(trafficSign, trafficSignService.additionalPublicId).map(_.asInstanceOf[AdditionalPanel])
    //val sortedAdditionalPanels = additionalPanels.sortBy(_.formPosition)
    val types = additionalPanels.flatMap{ additionalPanel =>
      HazmatTransportProhibitionClass.fromTrafficSign(TrafficSignType.applyOTHValue(additionalPanel.panelType))
    }

    val validityPeriods: Set[ValidityPeriod] =
      additionalPanels.flatMap { additionalPanel =>
        val trafficSignType = TrafficSignType.applyOTHValue(additionalPanel.panelType)
        createValidPeriod(trafficSignType, additionalPanel)
      }.toSet

      types.map(typeId => ProhibitionValue(typeId.value, validityPeriods, Set()))
    }
    if(values.nonEmpty) Some(Prohibitions(values)) else None
  }

  override def signBelongTo(trafficSign: PersistedTrafficSign): Boolean = {
    if (debbuger) println("signBelongTo")
    val signType = trafficSignService.getProperty(trafficSign, trafficSignService.typePublicId).get.propertyValue.toInt
    TrafficSignManager.belongsToHazmat(signType)
  }

  override def updateLinearAsset(oldAssetId: Long, newValue: Value, username: String): Seq[Long] = {
    if (debbuger) println("updateLinearAsset")
    hazmatTransportProhibitionService.updateWithoutTransaction(Seq(oldAssetId), newValue, username)
  }

  override def createLinearAsset(newSegment: TrafficSignToLinear, username: String) : Long = {
    if (debbuger) println("createLinearAsset")
    hazmatTransportProhibitionService.createWithoutTransaction(assetType, newSegment.roadLink.linkId, newSegment.value,
      newSegment.sideCode.value, Measures(newSegment.startMeasure, newSegment.endMeasure), username,
      vvhClient.roadLinkData.createVVHTimeStamp(), Some(newSegment.roadLink))
  }
}