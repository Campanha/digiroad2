package fi.liikennevirasto.digiroad2.dataimport

import java.io.InputStreamReader

import fi.liikennevirasto.digiroad2.{DigiroadEventBus, _}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.authentication.RequestHeaderAuthentication
import fi.liikennevirasto.digiroad2.middleware.{AdministrativeValues, CsvDataImporterInfo, NumericValues}
import fi.liikennevirasto.digiroad2.user.UserProvider
import fi.liikennevirasto.digiroad2.util.MassTransitStopExcelDataImporter
import org.joda.time.DateTime
import org.json4s.{CustomSerializer, DefaultFormats, Formats, JString}
import org.scalatra._
import org.scalatra.servlet.{FileItem, FileUploadSupport, MultipartConfig}
import org.scalatra.json.JacksonJsonSupport
import fi.liikennevirasto.digiroad2.asset.DateParser._
import fi.liikennevirasto.digiroad2.dao.ImportLogDAO
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService

class ImportDataApi(roadLinkService: RoadLinkService, val userProvider: UserProvider = Digiroad2Context.userProvider, val eventBus: DigiroadEventBus = Digiroad2Context.eventbus)
  extends ScalatraServlet with FileUploadSupport with JacksonJsonSupport with RequestHeaderAuthentication {

  case object DateTimeSerializer extends CustomSerializer[DateTime](format => ( {
    case _ => throw new NotImplementedError("DateTime deserialization")
  }, {
    case d: DateTime => JString(d.toString(DateTimePropertyFormat))
  }))

  protected implicit val jsonFormats: Formats = DefaultFormats + DateTimeSerializer
  private val CSV_LOG_PATH = "/tmp/csv_data_import_logs/"

  lazy val csvDataImporter = new CsvDataImporter(roadLinkService, eventBus)
  private final val threeMegabytes: Long = 3*1024*1024

  val importLogDao: ImportLogDAO = new ImportLogDAO

  before() {
    contentType = formats("json")
    configureMultipartHandling(MultipartConfig(maxFileSize = Some(threeMegabytes)))
    try {
      authenticateForApi(request)(userProvider)
    } catch {
      case ise: IllegalStateException => halt(Unauthorized("Authentication error: " + ise.getMessage))
    }
    response.setHeader("Digiroad2-Server-Originated-Response", "true")
  }

  post("/maintenanceRoads") {
    if (!userProvider.getCurrentUser().isOperator()) {
      halt(Forbidden("Vain operaattori voi suorittaa Excel-ajon"))
    }
   importMaintenanceRoads(fileParams("csv-file"))
  }

  post("/trafficSigns") {
    val municipalitiesToExpire = request.getParameterValues("municipalityNumbers") match {
      case null => Set.empty[Int]
      case municipalities => municipalities.map(_.toInt).toSet
    }

    if (!(userProvider.getCurrentUser().isOperator() || userProvider.getCurrentUser().isMunicipalityMaintainer())) {
      halt(Forbidden("Vain operaattori tai kuntaylläpitäjä voi suorittaa Excel-ajon"))
    }

    if (userProvider.getCurrentUser().isMunicipalityMaintainer() && municipalitiesToExpire.diff(userProvider.getCurrentUser().configuration.authorizedMunicipalities).nonEmpty) {
      halt(Forbidden(s"Puuttuvat muokkausoikeukset jossain listalla olevassa kunnassa: ${municipalitiesToExpire.mkString(",")}"))
    }

   importTrafficSigns(fileParams("csv-file"), municipalitiesToExpire)
  }

  post("/:pointAssetTypeImport") {
    validateOperation()
    val assetType = params("pointAssetTypeImport")
    importPointAssets(fileParams("csv-file"), assetType)
  }

  post("/roadLinks") {
    if (!userProvider.getCurrentUser().isOperator()) {
      halt(Forbidden("Vain operaattori voi suorittaa Excel-ajon"))
    }
    importRoadLinks(fileParams("csv-file"))
  }

  post("/massTransitStop") {
    if (!userProvider.getCurrentUser().isOperator()) {
      halt(Forbidden("Vain operaattori voi suorittaa Excel-ajon"))
    }
    val administrativeClassLimitations: Set[AdministrativeClass] = Set(
      params.get("limit-import-to-roads").map(_ => State),
      params.get("limit-import-to-streets").map(_ => Municipality),
      params.get("limit-import-to-private-roads").map(_ => Private)
    ).flatten

    importMassTransitStop(fileParams("csv-file"), administrativeClassLimitations)
  }

  get("/log/:id") {
    params.getAs[Long]("id").flatMap(id =>  csvDataImporter.getById(id)).getOrElse("Logia ei löytynyt.")
  }

  get("/log") {
    csvDataImporter.getByUser(userProvider.getCurrentUser().username)
  }

  get("/logs/:ids") {
    val ids = params("ids").split(',').map(_.toLong).toSet
    csvDataImporter.getByIds(ids)
  }

  //TODO check if this exist
  post("/csv") {
    if (!userProvider.getCurrentUser().isOperator()) {
      halt(Forbidden("Vain operaattori voi suorittaa Excel-ajon"))
    }
    val csvStream = new InputStreamReader(fileParams("csv-file").getInputStream)
    new MassTransitStopExcelDataImporter().updateAssetDataFromCsvFile(csvStream)
  }

  def validateOperation(): Unit = {
    if(!(userProvider.getCurrentUser().isOperator() || userProvider.getCurrentUser().isMunicipalityMaintainer())) {
      halt(Forbidden("Vain operaattori tai kuntaylläpitäjä voi suorittaa Excel-ajon"))
    }
  }

  def importTrafficSigns(csvFileItem: FileItem, municipalitiesToExpire: Set[Int]) : Option[ImportStatusInfo] = {
    val csvFileInputStream = csvFileItem.getInputStream
    val fileName = csvFileItem.getName
    if (csvFileInputStream.available() == 0)
      halt(BadRequest("Ei valittua CSV-tiedostoa. Valitse tiedosto ja yritä uudestaan."))
    else {
      val user = userProvider.getCurrentUser()
      val newLogId = createNewLog(user.username, fileName)
      eventBus.publish("importCSVData", CsvDataImporterInfo(TrafficSigns.layerName, fileName, userProvider.getCurrentUser(), csvFileInputStream, newLogId, municipalitiesToExpire.map(NumericValues)))
      getLogById(newLogId)
    }
  }

  def importPointAssets(csvFileItem: FileItem, layerName: String): Option[ImportStatusInfo] = {
    val fileName = csvFileItem.getName
    val csvFileInputStream = csvFileItem.getInputStream
    if(csvFileInputStream.available() == 0)
      halt(BadRequest("Ei valittua CSV-tiedostoa. Valitse tiedosto ja yritä uudestaan."))
    else {
      val user = userProvider.getCurrentUser()
      val newLogId = createNewLog(user.username, fileName)
      eventBus.publish("importCSVData", CsvDataImporterInfo(layerName, fileName, userProvider.getCurrentUser(), csvFileInputStream, newLogId))
      getLogById(newLogId)
    }
  }

  def importRoadLinks(csvFileItem: FileItem): Option[ImportStatusInfo] = {
    val csvFileInputStream = csvFileItem.getInputStream
    val fileName = csvFileItem.getName
    if (csvFileInputStream.available() == 0)
      halt(BadRequest("Ei valittua CSV-tiedostoa. Valitse tiedosto ja yritä uudestaan."))
    else {
      val user = userProvider.getCurrentUser()
      val newLogId = createNewLog(user.username, fileName)
      eventBus.publish("importCSVData", CsvDataImporterInfo("roadLinks", fileName, userProvider.getCurrentUser(), csvFileInputStream, newLogId))
      getLogById(newLogId)
    }
  }

  def importMaintenanceRoads(csvFileItem: FileItem): Option[ImportStatusInfo] = {
    val csvFileInputStream = csvFileItem.getInputStream
    val fileName = csvFileItem.getName
    if (csvFileInputStream.available() == 0)
      halt(BadRequest("Ei valittua CSV-tiedostoa. Valitse tiedosto ja yritä uudestaan."))
    else {
      val user = userProvider.getCurrentUser()
      val newLogId = createNewLog(user.username, fileName)
      eventBus.publish("importCSVData", CsvDataImporterInfo(MaintenanceRoadAsset.layerName, fileName, userProvider.getCurrentUser(), csvFileInputStream, newLogId))
      getLogById(newLogId)
    }
  }

  def importMassTransitStop(csvFileItem: FileItem, administrativeClassLimitations: Set[AdministrativeClass]) : Option[ImportStatusInfo] = {
    val csvFileInputStream = csvFileItem.getInputStream
    val fileName = csvFileItem.getName
    val user = userProvider.getCurrentUser()
    val newLogId = createNewLog(user.username, fileName)
    eventBus.publish("importCSVData", CsvDataImporterInfo(MassTransitStopAsset.layerName, fileName, user, csvFileInputStream, newLogId, administrativeClassLimitations.map(AdministrativeValues)))
    getLogById(newLogId)
  }

  def createNewLog(username: String, fileName: String) : Long =  OracleDatabase.withDynTransaction {importLogDao.create(username, fileName)}

  def getLogById(id: Long) : Option[ImportStatusInfo] =  OracleDatabase.withDynTransaction {importLogDao.get(id)}
}