package fi.liikennevirasto.digiroad2.dataimport

import java.io.InputStream

import fi.liikennevirasto.digiroad2.Digiroad2Context.{Digiroad2ServerOriginatedResponseHeader, userProvider}
import fi.liikennevirasto.digiroad2.authentication.RequestHeaderAuthentication
import fi.liikennevirasto.digiroad2.oracle.ImportLogService
import org.json4s.{DefaultFormats, Extraction, Formats}
import org.scalatra._
import org.scalatra.servlet.FileUploadSupport
import org.scalatra.json.JacksonJsonSupport

class ImportDataApi extends ScalatraServlet with FileUploadSupport with JacksonJsonSupport with RequestHeaderAuthentication {

  protected implicit val jsonFormats: Formats = DefaultFormats
  private val CSV_LOG_PATH = "/tmp/csv_data_import_logs/"
  private val ROAD_LINK_LOG = "road link import"
  private val TRAFFIC_SIGN_LOG = "traffic sign import"
  private val DELETE_TRAFFIC_SIGN_LOG = "traffic sign delete"
  private val MAINTENANCE_ROAD_LOG = "maintenance import"
  private val roadLinkCsvImporter = new RoadLinkCsvImporter
  private val trafficSignCsvImporter = new TrafficSignCsvImporter
  private val maintenanceRoadCsvImporter = new MaintenanceRoadCsvImporter

  private def verifyServiceToUse(assetType: String, csvFileInputStream: InputStream): CsvDataImporterOperations = {
    val user = userProvider.getCurrentUser()
    assetType match {
      case "trafficsigns" =>
        val municipalitiesToExpire = request.getParameterValues("municipalityNumbers") match {
          case null => Set.empty[Int]
          case municipalities => municipalities.map(_.toInt).toSet
        }

        if (!(user.isOperator() || user.isMunicipalityMaintainer())) {
          halt(Forbidden("Vain operaattori tai kuntaylläpitäjä voi suorittaa Excel-ajon"))
        }

        if (user.isMunicipalityMaintainer() && municipalitiesToExpire.diff(user.configuration.authorizedMunicipalities).nonEmpty) {
          halt(Forbidden(s"Puuttuvat muokkausoikeukset jossain listalla olevassa kunnassa: ${municipalitiesToExpire.mkString(",")}"))
        }

        importTrafficSigns(csvFileInputStream, municipalitiesToExpire)
      case "maintenanceRoads" =>
        if (!user.isOperator()) {
          halt(Forbidden("Vain operaattori voi suorittaa Excel-ajon"))
        }
        importMaintenanceRoads(csvFileInputStream)
      case _ =>
        if (!user.isOperator()) {
          halt(Forbidden("Vain operaattori voi suorittaa Excel-ajon"))
        }
        importRoadLinks(csvFileInputStream)
    }
  }

  before() {
    contentType = formats("json")
    try {
      authenticateForApi(request)(userProvider)
    } catch {
      case ise: IllegalStateException => halt(Unauthorized("Authentication error: " + ise.getMessage))
    }
    response.setHeader(Digiroad2ServerOriginatedResponseHeader, "true")
  }

  def importTrafficSigns(csvFileInputStream: InputStream, municipalitiesToExpire: Set[Int]): Nothing = {
    val id = ImportLogService.save("Kohteiden lataus on käynnissä. Päivitä sivu hetken kuluttua uudestaan.", TRAFFIC_SIGN_LOG)
    try {
      val result = trafficSignCsvImporter.importTrafficSigns(csvFileInputStream, municipalitiesToExpire)
      val response = result match {
        case trafficSignCsvImporter.ImportResult(Nil, Nil, Nil, Nil, _) => "CSV tiedosto käsitelty." //succesfully processed
        case trafficSignCsvImporter.ImportResult(Nil, excludedLinks, Nil, Nil, _) => "CSV tiedosto käsitelty. Seuraavat päivitykset on jätetty huomioimatta:\n" + pretty(Extraction.decompose(excludedLinks)) //following links have been excluded
        case _ => pretty(Extraction.decompose(result.copy(createdData = Nil)))
      }
      ImportLogService.save(id, response, TRAFFIC_SIGN_LOG)
    } catch {
      case e: Exception =>
        ImportLogService.save(id, "Latauksessa tapahtui odottamaton virhe: " + e.toString, TRAFFIC_SIGN_LOG) //error when saving log
        throw e
    } finally {
      csvFileInputStream.close()
    }
    redirect(url("/log/" + id + "/" + TRAFFIC_SIGN_LOG))
  }

  def importRoadLinks(csvFileInputStream: InputStream): Nothing = {
    val id = ImportLogService.save("Kohteiden lataus on käynnissä. Päivitä sivu hetken kuluttua uudestaan.", ROAD_LINK_LOG)
    try {
      val result = roadLinkCsvImporter.importLinkAttribute(csvFileInputStream)
      val response = result match {
        case roadLinkCsvImporter.ImportResult(Nil, Nil, Nil, Nil) => "CSV tiedosto käsitelty." //succesfully processed
        case roadLinkCsvImporter.ImportResult(Nil, Nil, Nil, excludedLinks) => "CSV tiedosto käsitelty. Seuraavat päivitykset on jätetty huomioimatta:\n" + pretty(Extraction.decompose(excludedLinks)) //following links have been excluded
        case _ => pretty(Extraction.decompose(result))
      }
      ImportLogService.save(id, response, ROAD_LINK_LOG)
    } catch {
      case e: Exception => {
        ImportLogService.save(id, "Latauksessa tapahtui odottamaton virhe: " + e.toString(), ROAD_LINK_LOG) //error when saving log
        throw e
      }
    } finally {
      csvFileInputStream.close()
    }
    redirect(url("/log/" + id + "/" + ROAD_LINK_LOG))
  }

  def importMaintenanceRoads(csvFileInputStream: InputStream): Nothing = {
    val id = ImportLogService.save("Kohteiden lataus on käynnissä. Päivitä sivu hetken kuluttua uudestaan.", MAINTENANCE_ROAD_LOG)
    try {
      val result = maintenanceRoadCsvImporter.importMaintenanceRoads(csvFileInputStream)
      val response = result match {
        case maintenanceRoadCsvImporter.ImportResult(Nil, Nil, Nil) => "CSV tiedosto käsitelty." //succesfully processed
        case maintenanceRoadCsvImporter.ImportResult(Nil, excludedLinks, Nil) => "CSV tiedosto käsitelty. Seuraavat päivitykset on jätetty huomioimatta:\n" + pretty(Extraction.decompose(excludedLinks)) //following links have been excluded
        case _ => pretty(Extraction.decompose(result))
      }
      ImportLogService.save(id, response, MAINTENANCE_ROAD_LOG)
    } catch {
      case e: Exception => {
        ImportLogService.save(id, "Latauksessa tapahtui odottamaton virhe: " + e.toString(), MAINTENANCE_ROAD_LOG) //error when saving log
        throw e
      }
    } finally {
      csvFileInputStream.close()
    }
    redirect(url("/log/" + id + "/" + MAINTENANCE_ROAD_LOG))
  }

  get("/log/:id/:assetTypeLog") {
    params.getAs[Long]("id").flatMap(id => ImportLogService.get(id, params("assetTypeLog"))).getOrElse("Logia ei löytynyt.")
  }

  post("/csv") {
    val csvFileInputStream = fileParams("csv-file").getInputStream
    if (csvFileInputStream.available() == 0) halt(BadRequest("Ei valittua CSV-tiedostoa. Valitse tiedosto ja yritä uudestaan.")) else None
    val assetType = params.getOrElse("asset-type", halt(BadRequest("Import not supported for selected asset type")))

    verifyServiceToUse(assetType, csvFileInputStream)
  }
}
