package fi.liikennevirasto.viite
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.SuravageLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, _}
import fi.liikennevirasto.digiroad2.linearasset.{RoadLinkLike,RoadLink}
import fi.liikennevirasto.digiroad2.masstransitstop.oracle.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException, Track}
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectState._
import fi.liikennevirasto.viite.dao.{ProjectDAO, RoadAddressDAO, _}
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.{GuestimateGeometryForMissingLinks, ProjectLinkSplitter, SplitOptions}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal
case class PreFillInfo(RoadNumber:BigInt, RoadPart:BigInt)
case class LinkToRevert(id:Long, linkId: Long, status: Long)
class ProjectService(roadAddressService: RoadAddressService, roadLinkService: RoadLinkService, eventbus: DigiroadEventBus, frozenTimeVVHAPIServiceEnabled: Boolean = false) {
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)
  private val guessGeom= new GuestimateGeometryForMissingLinks
  private val logger = LoggerFactory.getLogger(getClass)
  val allowedSideCodes = List(SideCode.TowardsDigitizing, SideCode.AgainstDigitizing)

  /**
    *
    * @param roadNumber    Road's number (long)
    * @param roadStartPart Starting part (long)
    * @param roadEndPart   Ending part (long)
    * @return Optional error message, None if no error
    */
  def checkRoadPartsExist(roadNumber: Long, roadStartPart: Long, roadEndPart: Long): Option[String] = {
    withDynTransaction {
      if (!RoadAddressDAO.roadPartExists(roadNumber, roadStartPart)) {
        if (!RoadAddressDAO.roadNumberExists(roadNumber)) {
          Some("Tienumeroa ei ole olemassa, tarkista tiedot")
        }
        else //roadnumber exists, but starting roadpart not
          Some("Tiellä ei ole olemassa valittua alkuosaa, tarkista tiedot")
      } else if (!RoadAddressDAO.roadPartExists(roadNumber, roadEndPart)) { // ending part check
        Some("Tiellä ei ole olemassa valittua loppuosaa, tarkista tiedot")
      } else
        None
    }
  }

  /**
    * Checks that new road address is not already reserved (currently only checks road address table)
    *
    * @param roadNumber road number
    * @param roadPart road part number
    * @param project  road address project needed for id and error message
    * @return
    */
  def checkNewRoadPartAvailableForProject(roadNumber: Long, roadPart: Long, project: RoadAddressProject): Option[String] = {
    val isReserved = RoadAddressDAO.isNotAvailableForProject(roadNumber, roadPart, project.id)
    if (!isReserved) {
      None
    } else {
      val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
      Some(s"TIE $roadNumber OSA $roadPart on jo olemassa projektin alkupäivänä ${project.startDate.toString(fmt)}, tarkista tiedot") //message to user if address is already in use
    }
  }

  private def createProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = roadAddressProject.copy(id = id)
    ProjectDAO.createRoadAddressProject(project)
    val error = addLinksToProject(project)
    if (error.nonEmpty)
      throw new RoadPartReservedException(error.get)
    ProjectDAO.getRoadAddressProjectById(id).get
  }

  private def projectFound(roadAddressProject: RoadAddressProject): Option[RoadAddressProject] = {
    val newRoadAddressProject=0
    if (roadAddressProject.id==newRoadAddressProject) return None
    withDynTransaction {
      return ProjectDAO.getRoadAddressProjectById(roadAddressProject.id)
    }
  }

  def fetchPreFillFromVVH(linkId: Long): Either[String,PreFillInfo] = {
    parsePreFillData(roadLinkService.fetchVVHRoadlinks(Set(linkId),frozenTimeVVHAPIServiceEnabled))
  }

  def parsePreFillData(vvhRoadLinks: Seq[VVHRoadlink]): Either[String, PreFillInfo] = {
    if (vvhRoadLinks.isEmpty) {
      Left("Link could not be found in VVH")    }
    else {
      val vvhLink = vvhRoadLinks.head
      (vvhLink.attributes.get("ROADNUMBER"), vvhLink.attributes.get("ROADPARTNUMBER")) match {
        case (Some(roadNumber:BigInt), Some(roadPartNumber:BigInt)) => {
          Right(PreFillInfo(roadNumber,roadPartNumber))
        }
        case _ => Left("Link does not contain valid prefill info")
      }
    }
  }

  def checkRoadPartsReservable(roadNumber: Long, startPart: Long, endPart: Long): Either[String, Seq[ReservedRoadPart]] = {
    withDynTransaction {
      (startPart to endPart).foreach(part =>
        ProjectDAO.roadPartReservedByProject(roadNumber, part) match {
          case Some(name) => return Left(s"TIE $roadNumber OSA $part on jo varattuna projektissa $name, tarkista tiedot")
          case _ =>
        })
      Right((startPart to endPart).flatMap( part => getAddressPartInfo(roadNumber, part))
      )
    }
  }

  def validateProjectDate(reservedParts: Seq[ReservedRoadPart], date: DateTime): Option[String] = {
    reservedParts.foreach( part => {
      if(part.startDate.nonEmpty && part.startDate.get.isAfter(date))
        return Option(s"Tieosalla TIE ${part.roadNumber} OSA ${part.roadPartNumber} alkupäivämäärä " +
          s"${part.startDate.get.toString("dd.MM.yyyy")} on myöhempi kuin tieosoiteprojektin alkupäivämäärä " +
          s"${date.toString("dd.MM.yyyy")}, tarkista tiedot.")
      if(part.endDate.nonEmpty && part.endDate.get.isAfter(date))
        return Option(s"Tieosalla TIE ${part.roadNumber} OSA ${part.roadPartNumber} loppupäivämäärä " +
          s"${part.endDate.get.toString("dd.MM.yyyy")} on myöhempi kuin tieosoiteprojektin alkupäivämäärä " +
          s"${date.toString("dd.MM.yyyy")}, tarkista tiedot.")
    })
    None
  }

  private def getAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ReservedRoadPart] = {
    ProjectDAO.fetchReservedRoadPart(roadNumber, roadPart).orElse(generateAddressPartInfo(roadNumber, roadPart))
  }

  private def generateAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ReservedRoadPart] = {
    RoadAddressDAO.getRoadPartInfo(roadNumber, roadPart) match {
      case Some((partId, linkId, addrLength, discontinuity, startDate, endDate)) =>
        val roadLink = roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(Set(linkId), newTransaction = false, frozenTimeVVHAPIServiceEnabled)
        val ely: Option[Long] = roadLink.headOption.map(rl => MunicipalityDAO.getMunicipalityRoadMaintainers.getOrElse(rl.municipalityCode, -1))
        ely match {
          case Some(value) if value != -1 =>
            Some(ReservedRoadPart(0L, roadNumber, roadPart, addrLength, addrLength, Discontinuity.apply(discontinuity.toInt), value, startDate, endDate, Some(linkId)))
          case _ => None
        }
      case None =>
        None
    }
  }

  def createProjectLinks(linkIds: Set[Long], projectId: Long, roadNumber: Long, roadPartNumber:Long, trackCode: Int,
                         discontinuity: Int, roadType: Int, roadLinkSource: Int, roadEly: Long, user: String): Map[String, Any] = {
    val roadLinks = if(roadLinkSource == LinkGeomSource.SuravageLinkInterface.value) {
      getProjectSuravageRoadLinksByLinkIds(linkIds)
    } else {
      getProjectRoadLinksByLinkIds(linkIds)
    }
    setProjectEly(projectId, roadEly) match {
      case Some(errorMessage) => Map("success" -> false, "errormessage" -> errorMessage)
      case None => {
        addNewLinksToProject(roadLinks, projectId, roadNumber, roadPartNumber, trackCode, discontinuity, roadType, user) match {
          case Some(errorMessage) => Map("success" -> false, "errormessage" -> errorMessage)
          case None => Map ("success" -> true, "publishable" -> projectLinkPublishable(projectId))
        }
      }
    }
  }

  private def newProjectLink(projectAddressLink: ProjectAddressLink, project: RoadAddressProject, sideCode: SideCode, newTrackCode: Long,
                             newRoadNumber: Long, newRoadPartNumber: Long, newDiscontinuity: Int, newRoadType: Long, projectId: Long): ProjectLink = {
    toProjectLink(projectAddressLink, NewRoadAddress, Track.apply(newTrackCode.toInt), project, sideCode,
      newRoadNumber, newRoadPartNumber, newDiscontinuity, newRoadType, projectId, true)
  }

  private def toProjectLink(projectAddressLink: ProjectAddressLink, id: Long, track: Track, project: RoadAddressProject,
                            sideCode: SideCode, newRoadNumber: Long, newRoadPartNumber: Long, newDiscontinuity: Int, newRoadType: Long,
                            projectId: Long, isNewProjectLink: Boolean = false): ProjectLink = {
    ProjectLink(id, newRoadNumber, newRoadPartNumber, track,
      Discontinuity.apply(newDiscontinuity.toInt), projectAddressLink.startAddressM,
      projectAddressLink.endAddressM, Some(project.startDate), None, Some(project.createdBy), -1,
      projectAddressLink.linkId, projectAddressLink.startMValue, projectAddressLink.endMValue, sideCode,
      (projectAddressLink.startCalibrationPoint, projectAddressLink.endCalibrationPoint), floating = false,
      projectAddressLink.geometry, projectId, if (isNewProjectLink) LinkStatus.New else projectAddressLink.status, RoadType.apply(newRoadType.toInt),
      projectAddressLink.roadLinkSource, projectAddressLink.length, projectAddressLink.roadAddressId)
  }

  /**
    * Used when adding road address that does not have previous address
    */
  def addNewLinksToProject(newLinks: Seq[ProjectAddressLink], roadAddressProjectID: Long, newRoadNumber: Long,
                           newRoadPartNumber: Long, newTrackCode: Long, newDiscontinuity: Long,
                           newRoadType: Long = RoadType.Unknown.value, user: String): Option[String] = {

    def matchSideCodes(newLink: ProjectAddressLink, existingLink: ProjectAddressLink): SideCode = {
      val (startP, endP) =GeometryUtils.geometryEndpoints(existingLink.geometry)

      if (GeometryUtils.areAdjacent(newLink.geometry.head, endP) ||
        GeometryUtils.areAdjacent(newLink.geometry.last, startP))
        existingLink.sideCode
      else {
        if (existingLink.sideCode.equals(AgainstDigitizing))
          TowardsDigitizing
        else
          AgainstDigitizing
      }
    }

    try {
      withDynTransaction {
        val roadPartLinks = withGeometry(ProjectDAO.fetchByProjectRoadPart(newRoadNumber, newRoadPartNumber, roadAddressProjectID))
        val linksInProject = getLinksByProjectLinkId(roadPartLinks.map(l => l.linkId).toSet, roadAddressProjectID, false)
        val randomSideCode =
          linksInProject.filterNot(link => link.status == LinkStatus.Terminated).map(l =>
            l -> newLinks.find(n => GeometryUtils.areAdjacent(l.geometry, n.geometry))).toMap.find { case (l, n) => n.nonEmpty }.map {
            case (l, Some(n)) =>
              matchSideCodes(n, l)
            case _ => SideCode.TowardsDigitizing
          }.getOrElse(SideCode.TowardsDigitizing)
        val project = getProjectWithReservationChecks(roadAddressProjectID, newRoadNumber, newRoadPartNumber)
        if (!project.isReserved(newRoadNumber, newRoadPartNumber))
          ProjectDAO.reserveRoadPart(project.id, newRoadNumber, newRoadPartNumber, project.modifiedBy)
        val newProjectLinks = newLinks.map(projectLink => {
          projectLink.linkId ->
            newProjectLink(projectLink, project, randomSideCode, newTrackCode, newRoadNumber, newRoadPartNumber,
              newDiscontinuity.toInt, newRoadType, roadAddressProjectID)
        }).toMap
        if (GeometryUtils.isNonLinear(newProjectLinks.values.toSeq))
          throw new ProjectValidationException("Valittu tiegeometria sisältää haarautumia ja pitää käsitellä osina. Tallennusta ei voi tehdä.")
        val existingLinks = roadPartLinks.filterNot(l => newProjectLinks.contains(l.linkId))
        val combinedLinks = existingLinks ++ newProjectLinks.values.toSeq
        //Determine geometries for the mValues and addressMValues
        val linksWithMValues = ProjectSectionCalculator.assignMValues(combinedLinks)
        val (toCreate, toUpdate) = linksWithMValues.partition(_.id == NewRoadAddress)
        ProjectDAO.updateProjectLinksToDB(toUpdate, user)
        ProjectDAO.create(toCreate)
        None
      }
    } catch {
      case ex: ProjectValidationException => Some(ex.getMessage)
    }
  }

  def getFirstProjectLink(project: RoadAddressProject): Option[ProjectLink] = {
    project.reservedParts.find(_.startingLinkId.nonEmpty) match {
      case Some(rrp) =>
        withDynSession {
          ProjectDAO.fetchFirstLink(project.id, rrp.roadNumber, rrp.roadPartNumber).flatMap(pl =>
            withGeometry(Seq(pl)).headOption)
        }
      case _ => None
    }
  }

  private def withGeometry(projectLinks: Seq[ProjectLink], resetAddress: Boolean = false): Seq[ProjectLink] = {
    val (withGeom, without) = projectLinks.partition(_.geometry.length > 1)
    val (suravageLinks, roadLinks) = without.partition(_.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
    val linkGeometries = (roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(roadLinks.map(_.linkId).toSet,
      false, frozenTimeVVHAPIServiceEnabled).map(pal => pal.linkId -> pal.geometry)
      ++ (if (suravageLinks.nonEmpty)
      roadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(suravageLinks.map(_.linkId).toSet, false).map(pal => pal.linkId -> pal.geometry) else Seq())).toMap
    without.map{pl =>
      withGeometry(pl, linkGeometries(pl.linkId), resetAddress)}  ++ withGeom
  }

  private def withGeometry(pl: ProjectLink, linkGeometry: Seq[Point], resetAddress: Boolean) = {
    val geom = GeometryUtils.truncateGeometry2D(linkGeometry, pl.startMValue, pl.endMValue)
    pl.copy(geometry = geom,
      geometryLength = GeometryUtils.geometryLength(geom),
      startAddrMValue = if (resetAddress) 0L else pl.startAddrMValue,
      endAddrMValue = if (resetAddress) 0L else pl.endAddrMValue,
      calibrationPoints = if (resetAddress) (None, None) else pl.calibrationPoints)
  }

  def changeDirection(projectId : Long, roadNumber : Long, roadPartNumber : Long): Option[String] = {
    RoadAddressLinkBuilder.municipalityRoadMaintainerMapping // make sure it is populated outside of this TX
    try {
      withDynTransaction {
        val projectLinkIds = ProjectDAO.fetchProjectLinkIds(projectId, roadNumber, roadPartNumber)
        if (!projectLinkIds.contains(projectLinkIds.head)){
          return Some("Linkit kuuluvat useampaan projektiin")
        }
        if(ProjectDAO.projectLinksCountUnchanged(projectId, roadNumber, roadPartNumber) > 0)
          return Some("Tieosalle ei voi tehdä kasvusuunnan kääntöä, koska tieosalla on linkkejä, jotka on tässä projektissa määritelty säilymään ennallaan.")
        ProjectDAO.flipProjectLinksSideCodes(projectId, roadNumber, roadPartNumber)
        val projectLinks = ProjectDAO.getProjectLinks(projectId)
        val adjLinks = withGeometry(projectLinks, resetAddress = false)
        ProjectSectionCalculator.assignMValues(adjLinks).foreach(
          link => ProjectDAO.updateAddrMValues(link))
        None
      }
    } catch{
      case NonFatal(e) =>
        logger.info("Direction change failed", e)
        Some("Päivitys ei onnistunut")
    }
  }

  /**
    * Adds reserved road links (from road parts) to a road address project. Clears
    * project links that are no longer reserved for the project. Reservability is check before this.
    */
  private def addLinksToProject(project: RoadAddressProject): Option[String] = {
    def toProjectLink(roadTypeMap: Map[Long, RoadType])(roadAddress: RoadAddress): ProjectLink = {
      ProjectLink(id=NewRoadAddress, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
        roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
        roadAddress.endDate, modifiedBy=Option(project.createdBy), 0L, roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
        roadAddress.sideCode, roadAddress.calibrationPoints, floating=false, roadAddress.geometry, project.id,
        LinkStatus.NotHandled, roadTypeMap.getOrElse(roadAddress.linkId, RoadType.Unknown),roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id)
    }
    //TODO: Check that there are no floating road addresses present when starting
    logger.info(s"Adding reserved road parts with links to project ${project.id}")
    val projectLinks = ProjectDAO.getProjectLinks(project.id)
    logger.debug(s"Links fetched")
    project.reservedParts.foreach(p => logger.debug(s"Project has part ${p.roadNumber}/${p.roadPartNumber} in ${p.ely} (${p.addressLength} m)"))
    validateReservations(project.reservedParts, project.ely, project.id, projectLinks) match{
      case Some(error) => throw new RoadPartReservedException(error)
      case None => logger.debug(s"Validation passed")
        val addresses = project.reservedParts.flatMap { reservation =>
          logger.debug(s"Reserve $reservation")
          val addressesOnPart = RoadAddressDAO.fetchByRoadPart(reservation.roadNumber, reservation.roadPartNumber, false)
          val mapping = roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(addressesOnPart.map(_.linkId).toSet, false,frozenTimeVVHAPIServiceEnabled)
            .map(rl => rl.linkId -> RoadAddressLinkBuilder.getRoadType(rl.administrativeClass, rl.linkType)).toMap
          val reserved = checkAndReserve(project, reservation)
          if (reserved.isEmpty)
            throw new RuntimeException(s"Can't reserve road part ${reservation.roadNumber}/${reservation.roadPartNumber}")
          val generatedInfo = generateAddressPartInfo(reservation.roadNumber, reservation.roadPartNumber) match {
            case Some(info) =>
              info.copy(id = reserved.get.id)
            case None => reservation.copy(id = reserved.get.id)
          }
          val projectLinks = addressesOnPart.map(toProjectLink(mapping))
          ProjectDAO.updateReservedRoadPart(generatedInfo)
          logger.debug(s"New parts updated $generatedInfo")
          projectLinks
        }
        logger.debug(s"Reserve done")
        val linksOnRemovedParts = projectLinks.filterNot(pl => project.reservedParts.exists(_.holds(pl)))
        val newProjectLinks = addresses.filterNot {
          ad => projectLinks.exists(pl => pl.roadNumber == ad.roadNumber && pl.roadPartNumber == ad.roadPartNumber)
        }
        logger.debug(s"Removed / new links ready")
        if (linksOnRemovedParts.nonEmpty) {
          ProjectDAO.removeProjectLinksById(linksOnRemovedParts.map(_.id).toSet)
        }
        logger.debug(s"Removed deleted ${linksOnRemovedParts.size}")
        ProjectDAO.create(newProjectLinks)
        logger.debug(s"New links created ${newProjectLinks.size}")
        if (project.ely.isEmpty) {
          val ely = ProjectDAO.fetchReservedRoadParts(project.id).find(_.ely != -1).map(_.ely)
          if (ely.nonEmpty)
            ProjectDAO.updateProjectEly(project.id, ely.get)
        }
        logger.info(s"Adding reserved road parts finished for project ${project.id}")
        None
    }
  }

  private def validateReservations(reservedRoadParts: Seq[ReservedRoadPart], projectEly: Option[Long], projectId: Long, projectLinks: List[ProjectLink]): Option[String] = {
    val errors = reservedRoadParts.flatMap{ra =>
      val roadPartExistsInAddresses = RoadAddressDAO.roadPartExists(ra.roadNumber, ra.roadPartNumber) ||
        ProjectDAO.fetchProjectLinkIds(projectId, ra.roadNumber, ra.roadPartNumber).nonEmpty
      val projectLink = projectLinks.find(p => {
        ra.roadNumber == p.roadNumber && ra.roadPartNumber == p.roadPartNumber &&
          ra.discontinuity == p.discontinuity && ra.startDate == p.startDate &&
          ra.endDate == p.endDate
      })
      if ((!roadPartExistsInAddresses) && !existsInSuravageOrNew(projectLink)) {
        Some(s"TIE ${ra.roadNumber} OSA: ${ra.roadPartNumber}")
      } else
        None
    }
    val elyErrors = reservedRoadParts.flatMap(roadAddress =>
      if (projectEly.filterNot(l => l == -1L).getOrElse(roadAddress.ely) != roadAddress.ely) {
        Some(s"TIE ${roadAddress.roadNumber} OSA: ${roadAddress.roadPartNumber} (ELY ${roadAddress.ely} != ${projectEly.get})")
      } else None)
    if (errors.nonEmpty)
      Some(s"$ErrorFollowingRoadPartsNotFoundInDB ${errors.mkString(", ")}")
    else {
      if (elyErrors.nonEmpty)
        Some(s"$ErrorFollowingPartsHaveDifferingEly ${elyErrors.mkString(", ")}")
      else {
        val ely = reservedRoadParts.map(_.ely)
        if (ely.distinct.size > 1) {
          Some(ErrorRoadPartsHaveDifferingEly)
        } else {
          None
        }
      }
    }
  }

  def revertSplit(projectId: Long, linkId:Long): Option[String] = {
    withDynTransaction {
      val previousSplit = ProjectDAO.fetchSplitLinks(projectId, linkId)
      if (previousSplit.nonEmpty) {
        val (suravage, original) = previousSplit.partition(_.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
        revertLinks(projectId, previousSplit.head.roadNumber, previousSplit.head.roadPartNumber,
          suravage.map(link => LinkToRevert(link.id, link.linkId, link.status.value)),
          original.map(link => LinkToRevert(link.id, link.linkId, link.status.value)))
      } else
        Some(s"No split for link id $linkId found!")
    }
  }

  def splitSuravageLink(linkId:Long, username:String,
                        splitOptions: SplitOptions): Option[String] = {
    withDynSession {
      splitSuravageLinkInTX(linkId, username, splitOptions)
    }
  }

  def splitSuravageLinkInTX(linkId:Long, username:String,
                            splitOptions: SplitOptions): Option[String] = {
    val sOption = getProjectSuravageRoadLinksByLinkIds(Set(linkId)).headOption
    if (sOption.isEmpty) {
      Some(ErrorSuravageLinkNotFound)
    } else {
      val projectId = splitOptions.projectId
      val previousSplit = ProjectDAO.fetchSplitLinks(projectId, linkId)
      if (previousSplit.nonEmpty) {
        val (suravage, original) = previousSplit.partition(_.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
        revertLinks(projectId, previousSplit.head.roadNumber, previousSplit.head.roadPartNumber,
          suravage.map(link => LinkToRevert(link.id, link.linkId, link.status.value)),
          original.map(link => LinkToRevert(link.id, link.linkId, link.status.value)))
      }
      val suravageLink = sOption.get
      val endPoints = GeometryUtils.geometryEndpoints(suravageLink.geometry)
      val x = if (endPoints._1.x > endPoints._2.x) (endPoints._2.x, endPoints._1.x) else (endPoints._1.x, endPoints._2.x)
      val rightTop = Point(x._2, endPoints._2.y)
      val leftBottom = Point(x._1, endPoints._1.y)
      val projectLinks = getProjectLinksInBoundingBox(BoundingRectangle(leftBottom, rightTop), projectId)
      val suravageProjectLink = suravageLink
      val projectLinksConnected = projectLinks.filter(l =>
        GeometryUtils.areAdjacent(l.geometry, suravageProjectLink.geometry))
      //we rank template links near suravagelink by how much they overlap with suravage geometry
      val commonSections = projectLinksConnected.map(x =>
        x -> ProjectLinkSplitter.findMatchingGeometrySegment(suravageLink, x).map(GeometryUtils.geometryLength)
          .getOrElse(0.0)).filter(_._2 > MinAllowedRoadAddressLength)
      if (commonSections.isEmpty)
        Some(ErrorNoMatchingProjectLinkForSplit)
      else {
        val bestFit = commonSections.maxBy(_._2)._1
        val project = ProjectDAO.getRoadAddressProjectById(projectId).get
        val splitLinks = ProjectLinkSplitter.split(newProjectLink(suravageProjectLink, project, SideCode.TowardsDigitizing,
          Track.Unknown.value, 0L, 0L, 0, RoadType.Unknown.value, projectId), bestFit, splitOptions)
        ProjectDAO.removeProjectLinksByLinkId(projectId, splitLinks.map(_.linkId).toSet)
        ProjectDAO.create(splitLinks.map(x => x.copy(modifiedBy = Some(username))))
        None
      }
    }

  }


  def getProjectLinksInBoundingBox(bbox:BoundingRectangle, projectId:Long): (Seq[ProjectLink]) =
  {
    val roadLinks = roadLinkService.getRoadLinksWithComplementaryFromVVH(bbox, Set(), false).map(rl => rl.linkId -> rl).toMap
    val projectLinks = ProjectDAO.getProjectLinksByProjectAndLinkId(roadLinks.keys,projectId).filter(_.status == LinkStatus.NotHandled)
    projectLinks.map(pl => withGeometry(pl, roadLinks(pl.linkId).geometry, false))
  }

  private def existsInSuravageOrNew(projectLink: Option[ProjectLink]): Boolean = {
    if (projectLink.isEmpty) {
      false
    } else {
      val link = projectLink.get
      if (link.linkGeomSource != LinkGeomSource.SuravageLinkInterface) {
        link.status == LinkStatus.New
      } else{
        if(roadLinkService.fetchSuravageLinksByLinkIdsFromVVH(Set(link.linkId)).isEmpty) {
          false
        } else true
      }
    }
  }

  private def isRoadPartTransfer(projectLinks: Seq[ProjectLink], updatedProjectLinks: Seq[ProjectLink], newRoadNumber: Long , newRoadPart: Long): Boolean = {
    projectLinks.exists(l => l.roadNumber == newRoadNumber && l.roadPartNumber == newRoadPart) match {
      case true => !updatedProjectLinks.exists(_.roadPartNumber == newRoadPart) || !updatedProjectLinks.exists(_.roadNumber == newRoadNumber)
      case _ => false
    }
  }

  /**
    * Save road link project, reserve new road parts, free previously reserved road parts that were removed
    * @param roadAddressProject Updated road address project case class
    * @return Updated project reloaded from the database
    */
  def saveProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    if (projectFound(roadAddressProject).isEmpty)
      throw new IllegalArgumentException("Project not found")
    withDynTransaction {
      val storedProject = ProjectDAO.getRoadAddressProjectById(roadAddressProject.id).get
      val removed = storedProject.reservedParts.filterNot(part =>
        roadAddressProject.reservedParts.exists(rp => rp.roadPartNumber == part.roadPartNumber &&
          rp.roadNumber == part.roadNumber))
      removed.foreach(p => ProjectDAO.removeReservedRoadPart(roadAddressProject.id, p))
      addLinksToProject(roadAddressProject)
      ProjectDAO.updateRoadAddressProject(roadAddressProject)
      ProjectDAO.getRoadAddressProjectById(roadAddressProject.id).get
    }
  }

  def createRoadLinkProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    if (roadAddressProject.id != 0)
      throw new IllegalArgumentException(s"Road address project to create has an id ${roadAddressProject.id}")
    withDynTransaction {
      createProject(roadAddressProject)
    }
  }

  def getRoadAddressSingleProject(projectId: Long): Option[RoadAddressProject] = {
    withDynTransaction {
      ProjectDAO.getRoadAddressProjects(projectId).headOption
    }
  }

  def getRoadAddressAllProjects(): Seq[RoadAddressProject] = {
    withDynTransaction {
      ProjectDAO.getRoadAddressProjects()
    }
  }

  def getSplitLinkData(projectId: Long, linkId: Long): Seq[ProjectLink] = {
    withDynTransaction {
      ProjectDAO.fetchSplitLinks(projectId, linkId)
    }
  }

  /**
    * Check that road part is available for reservation and return the id of reserved road part table row.
    * Reservation must contain road number and road part number, other data is not used or saved.
    * @param project Project for which to reserve (or for which it is already reserved)
    * @param reservedRoadPart Reservation information (req: road number, road part number)
    * @return
    */
  private def checkAndReserve(project: RoadAddressProject, reservedRoadPart: ReservedRoadPart): Option[ReservedRoadPart] = {
    logger.info(s"Check ${project.id} matching to " + ProjectDAO.roadPartReservedTo(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber))
    ProjectDAO.roadPartReservedTo(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber) match {
      case Some(id) if id != project.id => None
      case Some(id) if id == project.id =>
        ProjectDAO.fetchReservedRoadPart(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber)
      case _ =>
        ProjectDAO.reserveRoadPart(project.id, reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber,
          project.modifiedBy)
        ProjectDAO.fetchReservedRoadPart(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber)
    }
  }

  def getProjectLinksWithSuravage(roadAddressService: RoadAddressService,projectId:Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false, publicRoads: Boolean=false): Seq[ProjectAddressLink] ={
    val combinedFuture=  for{
      fProjectLink <-  Future(getProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, frozenTimeVVHAPIServiceEnabled))
      fSuravage <- Future(roadAddressService.getSuravageRoadLinkAddresses(boundingRectangle, Set()))
    } yield (fProjectLink, fSuravage)
    val (projectLinkList,suravageList) =Await.result(combinedFuture, Duration.Inf)
    val projectSuravageLinkIds = projectLinkList.filter(_.roadLinkSource == SuravageLinkInterface).map(_.linkId).toSet
    roadAddressLinkToProjectAddressLink(suravageList.filterNot(s => projectSuravageLinkIds.contains(s.linkId))) ++
      projectLinkList
  }

  def getChangeProject(projectId:Long): Option[ChangeProject] = {
    val changeProjectData = withDynTransaction {
      try {
        val delta = ProjectDeltaCalculator.delta(projectId)
        if (setProjectDeltaToDB(delta, projectId)) {
          val roadAddressChanges = RoadAddressChangesDAO.fetchRoadAddressChanges(Set(projectId))
          Some(ViiteTierekisteriClient.convertToChangeProject(roadAddressChanges))
        } else {
          None
        }
      } catch {
        case NonFatal(e) =>
          logger.info(s"Change info not available for project $projectId: " + e.getMessage)
          None
      }
    }
    changeProjectData
  }

  def enrichTerminations(terminations: Seq[RoadAddress], roadlinks: Seq[RoadLink]): Seq[RoadAddress] = {
    val withRoadType = terminations.par.map{
      t =>
        val relatedRoadLink = roadlinks.find(rl => rl.linkId == t.linkId)
        relatedRoadLink match {
          case None => t
          case Some(rl) =>
            val roadType = RoadAddressLinkBuilder.getRoadType(rl.administrativeClass, rl.linkType)
            t.copy(roadType = roadType)
        }
    }
    withRoadType.toList
  }

  def getRoadAddressChangesAndSendToTR(projectId: Set[Long]) = {
    val roadAddressChanges = RoadAddressChangesDAO.fetchRoadAddressChanges(projectId)
    ViiteTierekisteriClient.sendChanges(roadAddressChanges)
  }

  def getProjectRoadLinksByLinkIds(linkIdsToGet : Set[Long], newTransaction : Boolean = true): Seq[ProjectAddressLink] = {

    if(linkIdsToGet.isEmpty)
      return Seq()

    val fetchVVHStartTime = System.currentTimeMillis()
    val complementedRoadLinks = roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(linkIdsToGet, newTransaction,frozenTimeVVHAPIServiceEnabled)
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))

    val projectRoadLinks = complementedRoadLinks
      .map { rl =>
        val ra = Seq()
        val missed =  Seq()
        rl.linkId -> roadAddressService.buildRoadAddressLink(rl, ra, missed)
      }.toMap

    val filledProjectLinks = RoadAddressFiller.fillTopology(complementedRoadLinks, projectRoadLinks)

    filledProjectLinks._1.map(toProjectAddressLink)

  }

  def getProjectSuravageRoadLinksByLinkIds(linkIdsToGet : Set[Long]): Seq[ProjectAddressLink] = {
    if(linkIdsToGet.isEmpty)
      Seq()
    else {
      val fetchVVHStartTime = System.currentTimeMillis()
      val suravageRoadLinks = roadAddressService.getSuravageRoadLinkAddressesByLinkIds(linkIdsToGet)
      val fetchVVHEndTime = System.currentTimeMillis()
      logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))
      suravageRoadLinks.map(toProjectAddressLink)
    }
  }

  def getLinksByProjectLinkId(linkIdsToGet : Set[Long], projectId: Long, newTransaction : Boolean = true): Seq[ProjectAddressLink] = {

    if(linkIdsToGet.isEmpty)
      return Seq()

    val fetchVVHStartTime = System.currentTimeMillis()
    val complementedRoadLinks = roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(linkIdsToGet, newTransaction, frozenTimeVVHAPIServiceEnabled)
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))
    val fetchProjectLinks = ProjectDAO.getProjectLinks(projectId).groupBy(_.linkId)

    val projectRoadLinks = complementedRoadLinks.map {
      rl =>
        val pl = fetchProjectLinks.getOrElse(rl.linkId, Seq())
        rl.linkId -> buildProjectRoadLink(rl, pl)
    }.toMap

    RoadAddressFiller.fillProjectTopology(complementedRoadLinks, projectRoadLinks)
  }

  def getProjectRoadLinks(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    def complementaryLinkFilter(roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                everything: Boolean = false, publicRoads: Boolean = false)(roadAddressLink: RoadAddressLink) = {
      everything || publicRoads || roadNumberLimits.exists {
        case (start, stop) => roadAddressLink.roadNumber >= start && roadAddressLink.roadNumber <= stop
      }
    }

    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val (floating, addresses) = RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating = false).partition(_.floating)
      (floating.groupBy(_.linkId), addresses.groupBy(_.linkId))
    })
    val fetchProjectLinksF = Future(withDynTransaction {
      ProjectDAO.getProjectLinks(projectId).groupBy(_.linkId)
    })
    val fetchVVHStartTime = System.currentTimeMillis()
    val (complementedRoadLinks, suravageLinks) = fetchRoadLinksWithComplementaryAndSuravage(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads)
    val complementaryLinkIds = complementedRoadLinks.filter(_.linkSource == LinkGeomSource.ComplimentaryLinkInterface).map(_.linkId)
    val linkIds = complementedRoadLinks.map(_.linkId).toSet ++ suravageLinks.map(_.linkId).toSet
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))

    val fetchMissingRoadAddressStartTime = System.currentTimeMillis()
    val ((floating, addresses), projectLinks) = Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)
    // TODO: When floating handling is enabled we need this - but ignoring the result otherwise here
    val missingLinkIds = linkIds -- floating.keySet -- addresses.keySet -- projectLinks.keySet

    val missedRL = withDynTransaction {
      RoadAddressDAO.getMissingRoadAddresses(missingLinkIds)
    }.groupBy(_.linkId)
    val fetchMissingRoadAddressEndTime = System.currentTimeMillis()
    logger.info("End fetch missing and floating road address in %.3f sec".format((fetchMissingRoadAddressEndTime - fetchMissingRoadAddressStartTime) * 0.001))

    val buildStartTime = System.currentTimeMillis()

    val projectRoadLinks = (complementedRoadLinks.map {
      rl =>
        val pl = projectLinks.getOrElse(rl.linkId, Seq())
        rl.linkId -> buildProjectRoadLink(rl, pl)
    } ++
      suravageLinks.map {
        sl =>
          val pl = projectLinks.getOrElse(sl.linkId, Seq())
          sl.linkId -> buildProjectRoadLink(sl, pl)
      }).filter(_._2.nonEmpty).toMap

    val filledProjectLinks = RoadAddressFiller.fillProjectTopology(complementedRoadLinks ++ suravageLinks, projectRoadLinks)

    val nonProjectRoadLinks = complementedRoadLinks.filterNot(rl => projectRoadLinks.keySet.contains(rl.linkId))

    val viiteRoadLinks = nonProjectRoadLinks
      .map { rl =>
        val ra = addresses.getOrElse(rl.linkId, Seq())
        val missed = missedRL.getOrElse(rl.linkId, Seq())
        rl.linkId -> roadAddressService.buildRoadAddressLink(rl, ra, missed)
      }.toMap

    val buildEndTime = System.currentTimeMillis()
    logger.info("End building road address in %.3f sec".format((buildEndTime - buildStartTime) * 0.001))

    val (filledTopology, _) = RoadAddressFiller.fillTopology(nonProjectRoadLinks, viiteRoadLinks)

    val returningTopology = filledTopology.filter(link => !complementaryLinkIds.contains(link.linkId) ||
      complementaryLinkFilter(roadNumberLimits, municipalities, everything, publicRoads)(link))
    returningTopology.map(toProjectAddressLink) ++ filledProjectLinks

  }

  def roadAddressLinkToProjectAddressLink(roadAddresses: Seq[RoadAddressLink]): Seq[ProjectAddressLink]= {
    roadAddresses.map(toProjectAddressLink)
  }

  private def getProjectWithReservationChecks(projectId: Long, newRoadNumber: Long, newRoadPart: Long): RoadAddressProject = {
    RoadAddressValidator.checkProjectExists(projectId)
    val project = ProjectDAO.getRoadAddressProjectById(projectId).get
    RoadAddressValidator.checkAvailable(newRoadNumber, newRoadPart, project)
    RoadAddressValidator.checkNotReserved(newRoadNumber, newRoadPart, project)
    project
  }

  def revertLinks(links: Iterable[ProjectLink]): Option[String] = {
    if (links.groupBy(l => (l.projectId, l.roadNumber, l.roadPartNumber)).keySet.size != 1)
      throw new IllegalArgumentException("Reverting links from multiple road parts at once is not allowed")
    val l = links.head
    revertLinks(l.projectId, l.roadNumber, l.roadPartNumber, links.map(
      link => LinkToRevert(link.id, link.linkId, link.status.value)))
  }

  private def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, toRemove: Iterable[LinkToRevert],
                          modified: Iterable[LinkToRevert]) = {
    ProjectDAO.removeProjectLinksByLinkId(projectId, toRemove.map(_.linkId).toSet)
    val projectLinks = ProjectDAO.getProjectLinksByIds(modified.map(_.id))
    RoadAddressDAO.queryById(projectLinks.map(_.roadAddressId).toSet).foreach( ra =>
      ProjectDAO.updateProjectLinkValues(projectId, ra))
    val afterUpdateLinks = ProjectDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
    if (afterUpdateLinks.nonEmpty){
      val adjLinks = withGeometry(afterUpdateLinks)
      ProjectSectionCalculator.assignMValues(adjLinks).foreach(adjLink => ProjectDAO.updateAddrMValues(adjLink))
    }
    None
  }

  def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Iterable[LinkToRevert]): Option[String] = {
    try {
      withDynTransaction{
        val (added, modified) = links.partition(_.status == LinkStatus.New.value)
        revertLinks(projectId, roadNumber, roadPartNumber, added, modified)
      }
    }
    catch{
      case NonFatal(e) =>
        logger.info("Error reverting the changes on roadlink", e)
        Some("Virhe tapahtui muutosten palauttamisen yhteydessä")
    }
  }

  /**
    * Update project links to given status and recalculate delta and change table
    * @param projectId Project's id
    * @param linkIds Set of link ids that are set to this status
    * @param linkStatus New status for given link ids
    * @param userName Username of the user that does this change
    * @return true, if the delta calculation is successful and change table has been updated.
    */
  def updateProjectLinks(projectId: Long, linkIds: Set[Long], linkStatus: LinkStatus, userName: String,
                         roadNumber: Long = 0, roadPartNumber: Long = 0, userDefinedEndAddressM: Option[Int]): Option[String] = {
    try {
      withDynTransaction{
        val projectLinks = withGeometry(ProjectDAO.getProjectLinks(projectId))
        userDefinedEndAddressM.map(addressM => {
          val endSegment = projectLinks.maxBy(_.endAddrMValue)
          val calibrationPoint = UserDefinedCalibrationPoint(newCalibrationPointId, endSegment.id, projectId, endSegment.endMValue, addressM)
          // TODO: remove calibration points that exist elsewhere except at the link end or start
          val foundCalibrationPoint = CalibrationPointDAO.findCalibrationPointByRemainingValues(endSegment.id, projectId, endSegment.endMValue)
          if(foundCalibrationPoint.isEmpty)
            CalibrationPointDAO.createCalibrationPoint(calibrationPoint)
          else
            CalibrationPointDAO.updateSpecificCalibrationPointMeasures(foundCalibrationPoint.head.id, endSegment.endMValue, addressM)
          Seq(CalibrationPoint)
        })
        val (updatedProjectLinks, _) = projectLinks.partition(pl => linkIds.contains(pl.linkId))
        linkStatus match {
          case LinkStatus.Terminated => {
            //Fetching road addresses in order to obtain the original addressMValues, since we may not have those values on project_link table, after previous recalculations
            val roadAddresses = RoadAddressDAO.fetchByLinkId(updatedProjectLinks.map(pl => pl.linkId).toSet)
            val updatedPL = updatedProjectLinks.map(pl => {
              val roadAddress = roadAddresses.find(_.id == pl.roadAddressId).get
              pl.copy(startAddrMValue = roadAddress.startAddrMValue, endAddrMValue = roadAddress.endAddrMValue)
            })
            ProjectDAO.updateProjectLinksToDB(updatedPL.map(_.copy(status = linkStatus, calibrationPoints = (None, None))), userName)
          }
          case LinkStatus.Numbering => {
            val project = getProjectWithReservationChecks(projectId, roadNumber, roadPartNumber)
            if (!project.isReserved(roadNumber, roadPartNumber))
              ProjectDAO.reserveRoadPart(project.id, roadNumber, roadPartNumber, project.modifiedBy)
            ProjectDAO.updateProjectLinkNumbering(projectId, updatedProjectLinks.head.roadNumber, updatedProjectLinks.head.roadPartNumber, linkStatus, roadNumber, roadPartNumber, userName)
          }
          case LinkStatus.Transfer => {
            if (isRoadPartTransfer(projectLinks, updatedProjectLinks.filterNot(link => link.status == LinkStatus.Terminated), roadNumber, roadPartNumber)) {
              val updated = updatedProjectLinks.filterNot(link => link.status == LinkStatus.Terminated).map(updl => {
                updl.copy(roadNumber = roadNumber, roadPartNumber = roadPartNumber, status = linkStatus, calibrationPoints = (None, None))
              })
              ProjectDAO.updateProjectLinksToDB(updated, userName)
            } else {
              ProjectDAO.updateProjectLinks(updatedProjectLinks.filterNot(link => link.status == LinkStatus.Terminated).map(_.id).toSet, linkStatus, userName)
            }
          }
          case _ => ProjectDAO.updateProjectLinks(updatedProjectLinks.filterNot(link => link.status == LinkStatus.Terminated).map(_.id).toSet, linkStatus, userName)
        }
        recalculateProjectLinks(projectId, userName)
        None
      }
    } catch {
      case ex: RoadAddressException =>
        logger.info("Road address Exception: " + ex.getMessage)
        Some(s"Tieosoitevirhe: (${ex.getMessage}")
      case ex: ProjectValidationException => Some(ex.getMessage)
    }
  }

  private def recalculateProjectLinks(projectId: Long, userName: String) = {
    val projectLinks = withGeometry(ProjectDAO.getProjectLinks(projectId))
    projectLinks.groupBy(
      pl => (pl.roadNumber, pl.roadPartNumber)).foreach {
      grp =>
        val calibrationPoints = CalibrationPointDAO.fetchByRoadPart(projectId, grp._1._1, grp._1._2)
        val recalculatedProjectLinks = ProjectSectionCalculator.assignMValues(grp._2, calibrationPoints)
        ProjectDAO.updateProjectLinksToDB(recalculatedProjectLinks, userName)
    }
    recalculateChangeTable(projectId)
  }

  private def recalculateChangeTable(projectId: Long) = {
    try {
      val delta = ProjectDeltaCalculator.delta(projectId)
      setProjectDeltaToDB(delta, projectId) match {
        case true   => None
        case false  => Some("Delta calculation not possible")
      }
    } catch {
      case ex: RoadAddressException =>
        logger.info("Delta calculation not possible: " + ex.getMessage)
        Some(ex.getMessage)
      case ex: ProjectValidationException => Some(ex.getMessage)
    }
  }

  def projectLinkPublishable(projectId: Long): Boolean = {
    // TODO: add other checks after transfers etc. are enabled
    withDynSession{
      ProjectDAO.getProjectLinks(projectId, Some(LinkStatus.NotHandled)).isEmpty &&
        ProjectDAO.getProjectLinks(projectId).nonEmpty
    }
  }

  /** Nullifies projects tr_id attribute, changes status to unfinnished and saves tr_info value to status_info. Tries to append old status info if it is possible
    * otherwise it only takes first 300 chars
    *
    * @param projectId project-id
    * @return returns option error string
    */
  def removeRotatingTRId(projectId:Long): Option[String] = {
    withDynSession {
      val projects = ProjectDAO.getRoadAddressProjects(projectId)
      val rotatingTR_Id = ProjectDAO.getRotatingTRProjectId(projectId)
      ProjectDAO.updateProjectStatus(projectId,ProjectState.Incomplete)
      val addedStatus = if (rotatingTR_Id.isEmpty) "" else "[OLD TR_ID was " + rotatingTR_Id.head+ "]"
      if (projects.isEmpty)
        return Some("Projectia ei löytynyt")
      val project = projects.head
      appendStatusInfo(project,addedStatus)
    }
    None
  }

  /**
    * Tries to append old status info if it is possible
    * otherwise it only takes first 300 chars of the old status
    * @param project
    * @param appendMessage
    */
  private def appendStatusInfo(project :RoadAddressProject, appendMessage:String) ={
    val maxStringLenght = 1000
    project.statusInfo match { // before removing tr-id we want to save it in statusinfo if we need it later. Currently it is overwriten when we resend and get new error
      case Some(statusInfo) =>
        if ((statusInfo + appendMessage).length < maxStringLenght)
          ProjectDAO.updateProjectStateInfo(appendMessage + statusInfo, project.id)
        else if (statusInfo.length+appendMessage.length<600)
          ProjectDAO.updateProjectStateInfo(appendMessage + statusInfo.substring(0, 300),project.id)
      case None =>
        if (appendMessage.nonEmpty)
          ProjectDAO.updateProjectStateInfo(appendMessage, project.id)
    }
    ProjectDAO.removeRotatingTRProjectId(project.id)


  }

  /**
    * Publish project with id projectId
    *
    * @param projectId Project to publish
    * @return optional error message, empty if no error
    */
  def publishProject(projectId: Long): PublishResult = {
    // TODO: Check that project actually is finished: projectLinkPublishable(projectId)
    // TODO: Run post-change tests for the roads that have been edited and throw an exception to roll back if not acceptable
    withDynTransaction {
      try {
        val delta=ProjectDeltaCalculator.delta(projectId)
        if(!setProjectDeltaToDB(delta,projectId)) {return PublishResult(false, false, Some("Muutostaulun luonti epäonnistui. Tarkasta ely"))}
        val trProjectStateMessage = getRoadAddressChangesAndSendToTR(Set(projectId))
        if (trProjectStateMessage.status==ProjectState.Failed2GenerateTRIdInViite.value){
          return PublishResult(false, false, Some(trProjectStateMessage.reason))
        }
        trProjectStateMessage.status match {
          case it if 200 until 300 contains it => {
            setProjectStatusToSend2TR(projectId)
            PublishResult(true, true, Some(trProjectStateMessage.reason))
          }
          case _ => {
            //rollback
            PublishResult(true, false, Some(trProjectStateMessage.reason))
          }
        }
      } catch{
        case NonFatal(e) =>  PublishResult(false, false, None)
      }
    }
  }

  private def setProjectDeltaToDB(projectDelta:Delta, projectId:Long):Boolean = {
    RoadAddressChangesDAO.clearRoadChangeTable(projectId)
    RoadAddressChangesDAO.insertDeltaToRoadChangeTable(projectDelta, projectId)
  }

  private def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClass, ral.linkType, ral.roadLinkType,
      ral.constructionType, ral.roadLinkSource, ral.roadType, ral.roadName, ral.municipalityCode, ral.modifiedAt, ral.modifiedBy,
      ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity,
      ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint,
      ral.anomaly, ral.lrmPositionId, LinkStatus.Unknown, ral.id)
  }

  private def buildProjectRoadLink(rl: RoadLinkLike, projectLinks: Seq[ProjectLink]): Seq[ProjectAddressLink] = {
    val pl: Seq[ProjectLink] = projectLinks.size match {
      case 0 => return Seq()
      case 1 => projectLinks
      case _ => fuseProjectLinks(projectLinks)
    }
    pl.map(l => ProjectAddressLinkBuilder.build(rl, l))
  }

  private def fuseProjectLinks(links: Seq[ProjectLink]) = {
    val linkIds = links.map(_.linkId).distinct
    if (linkIds.size != 1)
      throw new IllegalArgumentException(s"Multiple road link ids given for building one link: ${linkIds.mkString(", ")}")
    if (links.exists(_.connectedLinkId.nonEmpty))
      links
    else {
      val (startM, endM, startA, endA) = (links.map(_.startMValue).min, links.map(_.endMValue).max,
        links.map(_.startAddrMValue).min, links.map(_.endAddrMValue).max)
      Seq(links.head.copy(startMValue = startM, endMValue = endM, startAddrMValue = startA, endAddrMValue = endA))
    }
  }

  private def fetchRoadLinksWithComplementaryAndSuravage(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                                         everything: Boolean = false, publicRoads: Boolean = false): (Seq[RoadLink], Seq[VVHRoadlink]) = {
    val combinedFuture=  for{
      fStandard <- Future(roadLinkService.getViiteRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads,frozenTimeVVHAPIServiceEnabled))
      fComplementary <- Future(roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities))
      fSuravage <- Future(roadLinkService.getSuravageLinksFromVVH(boundingRectangle,municipalities))
    } yield (fStandard, fComplementary, fSuravage)

    val (roadLinks, complementaryLinks, suravageLinks) = Await.result(combinedFuture, Duration.Inf)
    (roadLinks ++ complementaryLinks, suravageLinks)
  }

  private def fetchRoadLinksWithComplementary(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                              everything: Boolean = false, publicRoads: Boolean = false): (Seq[RoadLink], Set[Long]) = {
    val roadLinksF = Future(roadLinkService.getViiteRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads,frozenTimeVVHAPIServiceEnabled))
    val complementaryLinksF = Future(roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities))
    val (roadLinks, complementaryLinks) = Await.result(roadLinksF.zip(complementaryLinksF), Duration.Inf)
    (roadLinks ++ complementaryLinks, complementaryLinks.map(_.linkId).toSet)
  }

  def getProjectStatusFromTR(projectId: Long) = {
    ViiteTierekisteriClient.getProjectStatus(projectId)
  }

  private def getStatusFromTRObject(trProject:Option[TRProjectStatus]):Option[ProjectState] = {
    trProject match {
      case Some(trProjectobject) => mapTRStateToViiteState(trProjectobject.status.getOrElse(""))
      case None => None
      case _ => None
    }
  }

  private def getTRErrorMessage(trProject:Option[TRProjectStatus]):String = {
    trProject match {
      case Some(trProjectobject) => trProjectobject.errorMessage.getOrElse("")
      case None => ""
      case _ => ""
    }
  }

  def setProjectStatusToSend2TR(projectId:Long) :Unit=
  {
    ProjectDAO.updateProjectStatus(projectId, ProjectState.Sent2TR)
  }

  def updateProjectStatusIfNeeded(currentStatus:ProjectState, newStatus:ProjectState, errorMessage:String,projectId:Long) :(ProjectState)= {
    if (currentStatus.value!=newStatus.value && newStatus != ProjectState.Unknown)
    {
      val projects=ProjectDAO.getRoadAddressProjects(projectId)
      if (projects.nonEmpty && newStatus==ProjectState.ErroredInTR) // We write error message and clear old TR_ID which was stored there, so user wont see it in hower
        ProjectDAO.updateProjectStateInfo(errorMessage,projectId)
      ProjectDAO.updateProjectStatus(projectId,newStatus)
    }
    if (newStatus != ProjectState.Unknown){
      newStatus
    } else
    {
      currentStatus
    }
  }

  private def getProjectsPendingInTR:Seq[Long]= {
    withDynSession {
      ProjectDAO.getProjectsWithWaitingTRStatus()
    }
  }
  def updateProjectsWaitingResponseFromTR(): Unit =
  {
    val listOfPendingProjects=getProjectsPendingInTR
    for(project<-listOfPendingProjects)
    {
      try {
        withDynSession {
          logger.info(s"Checking status for $project")
          val newStatus = checkAndUpdateProjectStatus(project)
          logger.info(s"new status is $newStatus")
        }
      } catch {
        case t: Throwable => logger.warn(s"Couldn't update project $project", t)
      }
    }

  }

  private def checkAndUpdateProjectStatus(projectID: Long): ProjectState =
  {
    ProjectDAO.getRotatingTRProjectId(projectID).headOption match
    {
      case Some(trId) =>
        ProjectDAO.getProjectStatus(projectID).map { currentState =>
          logger.info(s"Current status is $currentState")
          val trProjectState = ViiteTierekisteriClient.getProjectStatusObject(trId)
          val newState = getStatusFromTRObject(trProjectState).getOrElse(ProjectState.Unknown)
          val errorMessage = getTRErrorMessage(trProjectState)
          logger.info(s"TR returned project status for $projectID: $currentState -> $newState, errMsg: $errorMessage")
          val updatedStatus = updateProjectStatusIfNeeded(currentState, newState, errorMessage, projectID)
          if (updatedStatus == Saved2TR)
            updateRoadAddressWithProjectLinks(updatedStatus, projectID)
          updatedStatus
        }.getOrElse(ProjectState.Unknown)
      case None=>
        logger.info(s"During status checking VIITE wasnt able to find TR_ID to project $projectID")
        appendStatusInfo(ProjectDAO.getRoadAddressProjectById(projectID).head," Failed to find TR-ID ")
        ProjectState.Unknown
    }
  }

  private def mapTRStateToViiteState(trState:String): Option[ProjectState] ={

    trState match {
      case "S" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "K" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "T" => Some(ProjectState.apply(ProjectState.Saved2TR.value))
      case "V" => Some(ProjectState.apply(ProjectState.ErroredInTR.value))
      case "null" => Some(ProjectState.apply(ProjectState.ErroredInTR.value))
      case _=> None
    }
  }

  def updateRoadAddressWithProjectLinks(newState: ProjectState, projectID: Long): Seq[Long] = {
    if(newState != Saved2TR){
      throw new RuntimeException(s"Project state not at Saved2TR: $newState")
    }
    val project=ProjectDAO.getRoadAddressProjectById(projectID)
    val projectStartDate= Some(project.head.startDate)
    val projectLinks=ProjectDAO.getProjectLinks(projectID)
    val floatingFalse=true
    val historyFalse=false
    if (projectLinks.isEmpty)
      throw new RuntimeException(s"Tried to import empty project to road address table after TR response : $newState")

    ProjectDAO.moveProjectLinksToHistory(projectID)

    val roadAddressIDsToExpire = RoadAddressDAO.fetchByLinkId(projectLinks.map( x => x.linkId ).toSet,floatingFalse,historyFalse)
    //Expiring all old addresses by their ID
    roadAddressService.expireRoadAddresses(roadAddressIDsToExpire.map( x => x.id ).toSet)
    //Create endDate rows for old data that is "valid" (row should be ignored after end_date)
    RoadAddressDAO.create(roadAddressIDsToExpire.map(x => x.copy(endDate = projectStartDate, id = NewRoadAddress)),
      Some(project.head.createdBy))
    //removing terminations and adding start date
    val roadAddressesToBeImported = projectLinks.filterNot(_.status==LinkStatus.Terminated)
      .map(x => x.copy(endDate = None, startDate =projectStartDate))
    //Create new rows to RoadAddress table defining when new address is used
    importProjectLinksToRoadAddressTable(roadAddressesToBeImported,roadAddressIDsToExpire,Some(project.head.createdBy))
  }

  private def importProjectLinksToRoadAddressTable(projectLinks:Seq[ProjectLink], existingRoadAddresses:Seq[RoadAddress],projectOwner:Option[String]) ={
    val existingRoadAddressLinkIds = existingRoadAddresses.map(x => x.linkId)
    val (existingProjectAddresses, newProjectLinks) = projectLinks.partition(x => existingRoadAddressLinkIds.contains(x.linkId))
    val (suravageProjectLinks, newNonSuravageLinks) = newProjectLinks.partition(x => x.linkGeomSource==LinkGeomSource.SuravageLinkInterface)
    //Fetch geometry for projectlinks from roadaddress table based on link-id
    val (roadLinksWithGeometry,addressesMissingGeometry) = convertProjectLinksToRoadAddressesWithRoadAddressGeometry(existingProjectAddresses,existingRoadAddresses)
    //Fetches  geometry for newlinks from VVH (excluding suravagelinks) and combines it with projectlinkdata
    val (newRoads,missingNewRoadGeometry) = convertProjectLinkToRoadAddressWithVVHLinkGeometry(newNonSuravageLinks,
      roadLinkService.fetchVVHRoadlinks(newNonSuravageLinks.map( x=> x.linkId).toSet,frozenTimeVVHAPIServiceEnabled))
    //Fetches geometry for suravagelinks from VVH suravageInterface and combines it to projectLinkdata
    val (newSuravageRoads,missingSuravageGeometry) = convertProjectLinkToRoadAddressWithVVHLinkGeometry(suravageProjectLinks,
      roadLinkService.fetchSuravageLinksByLinkIdsFromVVH(suravageProjectLinks.map( x=> x.linkId).toSet))
    val projectLinksWithGeometry=roadLinksWithGeometry++newRoads++newSuravageRoads
    val missingGeometry=addressesMissingGeometry++missingNewRoadGeometry++missingSuravageGeometry
    val guessGeometry=guessGeom.guestimateGeometry(missingGeometry.sortBy(x=>x.roadNumber).sortBy(x=>x.roadPartNumber).sortBy(x=>x.startAddrMValue),projectLinksWithGeometry)
    RoadAddressDAO.create(roadLinksWithGeometry++newRoads++newSuravageRoads++guessGeometry,projectOwner)
  }


  private def convertProjectLinkToRoadAddressWithVVHLinkGeometry(projectLinks: Seq[ProjectLink], vvhRoadLinks: Seq[VVHRoadlink]): (Seq[RoadAddress],Seq[RoadAddress])= {
    val mapped = projectLinks.map(pl => pl -> vvhRoadLinks.find(r => r.linkId == pl.linkId))
    val (withGeom, missingGeom) = mapped.partition(m => m._2.nonEmpty)

    (withGeom.map { case (pl, vvhLink) =>
      val (p1, p2) = if (pl.sideCode == SideCode.TowardsDigitizing)
        GeometryUtils.geometryEndpoints(vvhLink.get.geometry)
      else GeometryUtils.geometryEndpoints(vvhLink.get.geometry).swap

      RoadAddress(NewRoadAddress,pl.roadNumber,pl.roadPartNumber,pl.roadType,pl.track,
        pl.discontinuity,pl.startAddrMValue,pl.endAddrMValue,pl.startDate, pl.endDate,pl.modifiedBy,pl.lrmPositionId,pl.linkId,
        pl.startMValue,pl.endMValue,pl.sideCode,vvhLink.get.vvhTimeStamp,pl.calibrationPoints,pl.floating,
        Seq(p1, p2),pl.linkGeomSource)
    }, mapProjectLinksAsFloatingRoadAddresses(missingGeom.map(_._1)))
  }

  private def mapProjectLinksAsFloatingRoadAddresses(projectLinks: Seq[ProjectLink]) :Seq[RoadAddress]={
    projectLinks.map(x =>
      RoadAddress(NewRoadAddress,x.roadNumber,x.roadPartNumber,x.roadType,x.track,
        x.discontinuity,x.startAddrMValue,x.endAddrMValue,x.startDate, x.endDate,x.modifiedBy,x.lrmPositionId,x.linkId,
        x.startMValue,x.endMValue,x.sideCode,VVHClient.createVVHTimeStamp(),x.calibrationPoints,floating=true,Seq.empty[Point],x.linkGeomSource))
  }

  private def convertProjectLinksToRoadAddressesWithRoadAddressGeometry(projectLinks: Seq[ProjectLink],
                                                                        roadAddresses: Seq[RoadAddress]): (Seq[RoadAddress],Seq[RoadAddress]) = {
    // TODO: use road address id from Project Link to map 1-to-1, this will produce incorrect results on multiple segments on link
    val mapped = projectLinks.map(pl => pl -> roadAddresses.find(r => r.linkId == pl.linkId))
    val (withGeom, missingGeom) = mapped.partition(m => m._2.nonEmpty)

    (withGeom.map { case (pl, ra) =>
      RoadAddress(NewRoadAddress, pl.roadNumber, pl.roadPartNumber, pl.roadType, pl.track, pl.discontinuity, pl.startAddrMValue,
        pl.endAddrMValue, pl.startDate, pl.endDate, pl.modifiedBy, pl.lrmPositionId, pl.linkId, pl.startMValue, pl.endMValue, pl.sideCode,
        ra.get.adjustedTimestamp, pl.calibrationPoints, pl.floating, ra.get.geometry, pl.linkGeomSource)
    }, mapProjectLinksAsFloatingRoadAddresses(missingGeom.map(_._1)))
  }

  // TODO: remove when saving road type to project link table
  def withFetchedDataFromVVH(roadAddresses: Seq[RoadAddress], roadLinks: Map[Long, RoadLink], Type: Object): Seq[RoadAddress] = {
    val fetchedAddresses = Type match {
      case RoadType =>
        val withRoadType: Seq[RoadAddress] = roadAddresses.par.map {
          ra =>
            roadLinks.get(ra.linkId) match {
              case None => ra
              case Some(rl) =>
                val roadType = RoadAddressLinkBuilder.getRoadType(rl.administrativeClass, rl.linkType)
                ra.copy(roadType = roadType)
            }
        }.toList
        withRoadType
      case _ => roadAddresses
    }
    fetchedAddresses
  }

  def setProjectEly(currentProjectId:Long, newEly: Long): Option[String] = {
    withDynTransaction {
      getProjectEly(currentProjectId).filterNot(_ == newEly).map { currentProjectEly =>
        logger.info(s"The project can not handle multiple ELY areas (the project ELY range is $currentProjectEly). Recording was discarded.")
        s"Projektissa ei voi käsitellä useita ELY-alueita (projektin ELY-alue on $currentProjectEly). Tallennus hylättiin."
      }.orElse{
        ProjectDAO.updateProjectEly(currentProjectId, newEly)
        None
      }
    }
  }

  def getProjectEly(projectId: Long): Option[Long] = {
    ProjectDAO.getProjectEly(projectId)
  }

  case class PublishResult(validationSuccess: Boolean, sendSuccess: Boolean, errorMessage: Option[String])
}

class ProjectValidationException(s: String) extends RuntimeException {
  override def getMessage: String = s
}