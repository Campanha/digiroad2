package fi.liikennevirasto.digiroad2

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date

import scala.collection.GenTraversableOnce
import fi.liikennevirasto.digiroad2.asset.{Property, PropertyValue}
import fi.liikennevirasto.digiroad2.masstransitstop.oracle.Queries
import fi.liikennevirasto.digiroad2.util.{RoadAddress, RoadSide, Track}
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost, HttpPut}
import org.apache.http.entity.{ContentType, StringEntity}
import org.apache.http.impl.client.{CloseableHttpClient, HttpClientBuilder}
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Formats, StreamInput}
import org.slf4j.LoggerFactory

/**
  * Values for Stop type (Pysäkin tyyppi) enumeration
  */
sealed trait StopType {
  def value: String
  def propertyValues: Set[Int]
}
object StopType {
  val values = Set[StopType](Commuter, LongDistance, Combined, Virtual, Unknown)

  def apply(value: String): StopType = {
    values.find(_.value == value).getOrElse(Unknown)
  }

  def propertyValues() : Set[Int] = {
    values.flatMap(_.propertyValues)
  }

  case object Commuter extends StopType { def value = "paikallis"; def propertyValues = Set(2); }
  case object LongDistance extends StopType { def value = "kauko"; def propertyValues = Set(3); }
  case object Combined extends StopType { def value = "molemmat"; def propertyValues = Set(2,3); }
  case object Virtual extends StopType { def value = "virtuaali"; def propertyValues = Set(5); }
  case object Unknown extends StopType { def value = "tuntematon"; def propertyValues = Set(99); }  // Should not be passed on interface
}

/**
  * Values for Existence (Olemassaolo) enumeration
  */
sealed trait Existence {
  def value: String
  def propertyValue: Int
}
object Existence {
  val values = Set(Yes, No, Unknown)

  def apply(value: String): Existence = {
    values.find(_.value == value).getOrElse(Unknown)
  }

  def fromPropertyValue(value: String): Existence = {
    value match {
      case "1" => No
      case "2" => Yes
      case _ => Unknown
    }
  }

  case object Yes extends Existence { def value = "on"; def propertyValue = 2; }
  case object No extends Existence { def value = "ei"; def propertyValue = 1; }
  case object Unknown extends Existence { def value = "ei_tietoa"; def propertyValue = 99; }
}

/**
  * Values for Equipment (Varuste) enumeration
  */
sealed trait Equipment {
  def value: String
  def publicId: String
}
object Equipment {
  val values = Set[Equipment](Timetable, TrashBin, BikeStand, Lighting, Seat, Roof, RoofMaintainedByAdvertiser, ElectronicTimetables, CarParkForTakingPassengers, RaisedBusStop)

  def apply(value: String): Equipment = {
    values.find(_.value == value).getOrElse(Unknown)
  }

  def fromPublicId(value: String): Equipment = {
    values.find(_.publicId == value).getOrElse(Unknown)
  }

  case object Timetable extends Equipment { def value = "aikataulu"; def publicId = "aikataulu"; }
  case object TrashBin extends Equipment { def value = "roskis"; def publicId = "roska_astia"; }
  case object BikeStand extends Equipment { def value = "pyorateline"; def publicId = "pyorateline"; }
  case object Lighting extends Equipment { def value = "valaistus"; def publicId = "valaistus"; }
  case object Seat extends Equipment { def value = "penkki"; def publicId = "penkki"; }
  case object Roof extends Equipment { def value = "katos"; def publicId = "katos"; }
  case object RoofMaintainedByAdvertiser extends Equipment { def value = "mainoskatos"; def publicId = "mainoskatos"; }
  case object ElectronicTimetables extends Equipment { def value = "sahk_aikataulu"; def publicId = "sahkoinen_aikataulunaytto"; }
  case object CarParkForTakingPassengers extends Equipment { def value = "saattomahd"; def publicId = "saattomahdollisuus_henkiloautolla"; }
  case object RaisedBusStop extends Equipment { def value = "korotus"; def publicId = "korotettu"; }
  case object Unknown extends Equipment { def value = "UNKNOWN"; def publicId = "tuntematon"; }
}

/**
  * Values for Road side (Puoli) enumeration
  */
sealed trait TRRoadSide {
  def value: String
  def propertyValues: Set[Int]
}
object TRRoadSide {
  val values = Set(Right, Left, Off, Unknown)

  def apply(value: String): TRRoadSide = {
    values.find(_.value == value).getOrElse(Unknown)
  }

  def propertyValues() : Set[Int] = {
    values.flatMap(_.propertyValues)
  }

  case object Right extends TRRoadSide { def value = "oikea"; def propertyValues = Set(1) }
  case object Left extends TRRoadSide { def value = "vasen"; def propertyValues = Set(2) }
  case object Off extends TRRoadSide { def value = "paassa"; def propertyValues = Set(99) } // Not supported by OTH
  case object Unknown extends TRRoadSide { def value = "ei_tietoa"; def propertyValues = Set(0) }
}

case class TierekisteriMassTransitStop(nationalId: Long,
                                       liviId: String,
                                       roadAddress: RoadAddress,
                                       roadSide: TRRoadSide,
                                       stopType: StopType,
                                       express: Boolean,
                                       equipments: Map[Equipment, Existence] = Map(),
                                       stopCode: Option[String],
                                       nameFi: Option[String],
                                       nameSe: Option[String],
                                       modifiedBy: String,
                                       operatingFrom: Option[Date],
                                       operatingTo: Option[Date],
                                       removalDate: Option[Date],
                                       inventoryDate: Date)

case class TierekisteriError(content: Map[String, Any], url: String)

class TierekisteriClientException(response: String) extends RuntimeException(response)

/**
  * TierekisteriClient is a utility for using Tierekisteri (TR) bus stop REST API in OTH.
  *
  * @param tierekisteriRestApiEndPoint
  * @param tierekisteriEnabled
  *
  */
class TierekisteriClient(tierekisteriRestApiEndPoint: String, tierekisteriEnabled: Boolean, client: CloseableHttpClient) {

  protected implicit val jsonFormats: Formats = DefaultFormats

  private val dateFormat = "yyyy-MM-dd"
  private val serviceName = "pysakit"

  private val trNationalId = "valtakunnallinen_id"
  private val trRoadNumber = "tie"        // tienumero
  private val trRoadPartNumber = "aosa"   // tieosanumero
  private val trLane = "ajr"              // ajorata
  private val trDistance = "aet"          // etaisyys
  private val trSide = "puoli"
  private val trStopCode = "pysakin_tunnus"
  private val trNameFi = "nimi_fi"
  private val trStopType = "pysakin_tyyppi"
  private val trIsExpress = "pikavuoro"
  private val trOperatingFrom = "alkupvm"
  private val trOperatingTo = "loppupvm"
  private val trRemovalDate = "lakkautuspvm"
  private val trLiviId = "livitunnus"
  private val trNameSe = "nimi_se"
  private val trEquipment = "varusteet"
  private val trUser = "kayttajatunnus"
  private val trInventoryDate = "inventointipvm"

  private val serviceUrl : String = tierekisteriRestApiEndPoint + serviceName
  private def serviceUrl(id: String) : String = serviceUrl + "/" + id

  private def booleanCodeToBoolean: Map[String, Boolean] = Map("on" -> true, "ei" -> false)
  private def booleanToBooleanCode: Map[Boolean, String] = Map(true -> "on", false -> "ei")

  private lazy val logger = LoggerFactory.getLogger(getClass)

  /**
    * Return all bus stops currently active from Tierekisteri
    * Tierekisteri REST API endpoint: GET /pysakit/
    *
    * @return
    */
  def fetchActiveMassTransitStops(): Seq[TierekisteriMassTransitStop] = {
    request[List[Map[String, Any]]](serviceUrl) match {
      case Left(content) =>
        content.map{
          stopAsset =>
            mapFields(stopAsset)
        }
      case Right(error) => throw new TierekisteriClientException("Tierekisteri error: " + error.content.get("error").get.toString)
    }
  }

  /**
    * Returns the anwser to the question "Is Tierekisteri Enabled?".
    *
    * @return Type: Boolean - If TR client is enabled
    */
  def isTREnabled : Boolean = {
    tierekisteriEnabled
  }

  /**
    * Returns a bus stop based on OTH "yllapitajan_koodi" id
    * Tierekisteri REST API endpoint: GET /pysakit/{livitunn}
    *
    * @param id
    * @return
    */
  def fetchMassTransitStop(id: String): TierekisteriMassTransitStop = {
    request[Map[String, Any]](serviceUrl(id)) match {
      case Left(content) =>
        mapFields(content)
      case Right(error) => throw new TierekisteriClientException("Tierekisteri error: " + error.content.get("error").get.toString)
    }
  }

  /**
    * Creates a new bus stop to Tierekisteri.
    * Tierekisteri REST API endpoint: POST /pysakit/
    *
    * @param trMassTransitStop
    */
  def createMassTransitStop(trMassTransitStop: TierekisteriMassTransitStop): Unit ={
    post(serviceUrl, trMassTransitStop) match {
      case Some(error) => throw new TierekisteriClientException("Tierekisteri error: " + error.content.get("error").get.toString)
      case _ => ; // do nothing
    }
  }

  /**
    * Updates and/or invalidates a stop. (If valid_to is set, the stop is invalidated. Other data may be updated at the time, too)
    * Tierekisteri REST API endpoint: PUT /pysakit/{livitunn}
    *
    * @param trMassTransitStop
    */
  def updateMassTransitStop(trMassTransitStop: TierekisteriMassTransitStop): Unit ={
    put(serviceUrl(trMassTransitStop.liviId), trMassTransitStop) match {
      case Some(error) => throw new TierekisteriClientException("Tierekisteri error: " + error.content.get("error").get.toString)
      case _ => ;
    }
  }

  /**
    * Marks a bus stop to be removed. Only for error correcting purposes, for example when bus stop was accidentally added.
    * Tierekisteri REST API endpoint: DELETE /pysakit/{livitunn}
    *
    * @param id
    */
  def deleteMassTransitStop(id: String): Unit ={
    delete(serviceUrl(id)) match {
      case Some(error) => throw new TierekisteriClientException("Tierekisteri error: " + error.content.get("error").get.toString)
      case _ => ;
    }
  }

  private def request[T](url: String): Either[T, TierekisteriError] = {
    val request = new HttpGet(url)
    val response = client.execute(request)

    try {
      val statusCode = response.getStatusLine.getStatusCode
      if (statusCode >= 400)
        return Right(TierekisteriError(Map("error" -> "Request returned HTTP Error %d".format(statusCode)), url))
      Left(parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[T])
    } catch {
      case e: Exception => Right(TierekisteriError(Map("error" -> e.getMessage), url))
    } finally {
      response.close()
    }
  }

  private def post(url: String, trMassTransitStop: TierekisteriMassTransitStop): Option[TierekisteriError] = {
    val request = new HttpPost(url)
    request.setEntity(createJson(trMassTransitStop))
    val response = client.execute(request)
    try {
      val statusCode = response.getStatusLine.getStatusCode
      if (statusCode >= 400)
        return Some(TierekisteriError(Map("error" -> "Request returned HTTP Error %d".format(statusCode)), url))
     None
    } catch {
      case e: Exception => Some(TierekisteriError(Map("error" -> e.getMessage), url))
    } finally {
      response.close()
    }
  }

  private def put(url: String, tnMassTransitStop: TierekisteriMassTransitStop): Option[TierekisteriError] = {
    val request = new HttpPut(url)
    request.setEntity(createJson(tnMassTransitStop))
    val response = client.execute(request)
    try {
      val statusCode = response.getStatusLine.getStatusCode
      if (statusCode >= 400)
        return Some(TierekisteriError(Map("error" -> "Request returned HTTP Error %d".format(statusCode)), url))
      None
    } catch {
      case e: Exception => Some(TierekisteriError(Map("error" -> e.getMessage), url))
    } finally {
      response.close()
    }
  }

  private def delete(url: String): Option[TierekisteriError] = {
    val request = new HttpDelete(url)
    val response = client.execute(request)
    try {
      val statusCode = response.getStatusLine.getStatusCode
      if (statusCode >= 400)
        return Some(TierekisteriError(Map("error" -> "Request returned HTTP Error %d".format(statusCode)), url))
      None
    } catch {
      case e: Exception => Some(TierekisteriError(Map("error" -> e.getMessage), url))
    } finally {
      response.close()
    }
  }

  private def createJson(trMassTransitStop: TierekisteriMassTransitStop) = {

    val jsonObj = Map(
      trNationalId -> trMassTransitStop.nationalId,
      trLiviId -> trMassTransitStop.liviId,
      trRoadNumber -> trMassTransitStop.roadAddress.road,
      trRoadPartNumber -> trMassTransitStop.roadAddress.roadPart,
      trSide -> trMassTransitStop.roadSide.value,
      trLane -> trMassTransitStop.roadAddress.track.value,
      trDistance -> trMassTransitStop.roadAddress.mValue,
      trStopCode -> trMassTransitStop.stopCode,
      trIsExpress -> booleanToBooleanCode.get(trMassTransitStop.express),
      trNameFi -> trMassTransitStop.nameFi,
      trNameSe -> trMassTransitStop.nameSe,
      trUser -> trMassTransitStop.modifiedBy,
      trOperatingFrom -> convertDateToString(trMassTransitStop.operatingFrom),
      trOperatingTo -> convertDateToString(trMassTransitStop.operatingTo),
      trRemovalDate -> convertDateToString(trMassTransitStop.removalDate),
      trEquipment -> trMassTransitStop.equipments.map{
        case (equipment, existence) =>
          equipment.value -> existence.value
      }
    )

    val stopType: Map[String, Any] = trMassTransitStop.stopType match {
      case StopType.Unknown => Map()
      case _ => Map(trStopType -> trMassTransitStop.stopType.value)
    }

    val json = Serialization.write(jsonObj ++ stopType)
    // Print JSON sent to Tierekisteri for testing purposes
    //println(json)

    new StringEntity(json, ContentType.APPLICATION_JSON)
  }

  private def mapFields(data: Map[String, Any]): TierekisteriMassTransitStop = {
    def getFieldValue(field: String): Option[String] = {
      try {
        data.get(field).map(_.toString) match {
          case Some(value) => Some(value)
          case _ => None
        }
      } catch {
        case ex: NullPointerException => None
      }
    }
    def getMandatoryFieldValue(field: String): Option[String] = {
      val fieldValue = getFieldValue(field)
      if (fieldValue.isEmpty)
        throw new TierekisteriClientException("Missing mandatory field in response '%s'".format(field))
      fieldValue
    }

    //Mandatory fields
    val nationalId = convertToLong(getMandatoryFieldValue(trNationalId)).get
    val roadSide = TRRoadSide.apply(getMandatoryFieldValue(trSide).get)
    val express = booleanCodeToBoolean.getOrElse(getMandatoryFieldValue(trIsExpress).get, throw new TierekisteriClientException("The boolean code '%s' is not supported".format(getFieldValue(trIsExpress))))
    val liviId = getMandatoryFieldValue(trLiviId).get
    val stopType = StopType.apply(getMandatoryFieldValue(trStopType).get)
    val modifiedBy = getMandatoryFieldValue(trUser).get
    val roadAddress = RoadAddress(None, convertToInt(getMandatoryFieldValue(trRoadNumber)).get,
      convertToInt(getMandatoryFieldValue(trRoadPartNumber)).get,Track.Combined,convertToInt(getMandatoryFieldValue(trDistance)).get,None)
    val inventoryDate = convertToDate(getMandatoryFieldValue(trInventoryDate)).get

    //Not mandatory fields
    val equipments = extractEquipment(data)
    val stopCode = getFieldValue(trStopCode)
    val nameFi = getFieldValue(trNameFi)
    val nameSe = getFieldValue(trNameSe)
    val operatingFrom = convertToDate(getFieldValue(trOperatingFrom))
    val operatingTo = convertToDate(getFieldValue(trOperatingTo))
    val removalDate = convertToDate(getFieldValue(trRemovalDate))
    val inventoryDate = getFieldValue(trInventoryDate)

    TierekisteriMassTransitStop(nationalId,liviId, roadAddress, roadSide, stopType, express, equipments,
      stopCode, nameFi, nameSe, modifiedBy, operatingFrom, operatingTo, removalDate, inventoryDate)
  }

  private def extractEquipment(data: Map[String, Any]) : Map[Equipment, Existence] = {
    val equipmentData: Map[String, String] = data.get(trEquipment).nonEmpty match {
      case true => data.get(trEquipment).get.asInstanceOf[Map[String, String]]
      case false => Map()
    }

    Equipment.values.flatMap{ equipment =>
      equipmentData.get(equipment.value) match{
        case Some(value) =>
          Some(equipment -> Existence.apply(value))
        case None =>
          None
      }
    }.toMap
  }

  private def convertToLong(value: Option[String]): Option[Long] = {
    try {
      value.map(_.toLong)
    } catch {
      case e: NumberFormatException =>
        throw new TierekisteriClientException("Invalid value in response: Long expected, got '%s'".format(value))
    }
  }

  private def convertToInt(value: Option[String]): Option[Int] = {
    try {
      value.map(_.toInt)
    } catch {
      case e: NumberFormatException =>
        throw new TierekisteriClientException("Invalid value in response: Int expected, got '%s'".format(value))
    }
  }

  private def convertToDate(value: Option[String]): Option[Date] = {
    try {
      value.map(dv => new SimpleDateFormat(dateFormat).parse(dv))
    } catch {
      case e: ParseException =>
        throw new TierekisteriClientException("Invalid value in response: Date expected, got '%s'".format(value))
    }
  }

  private def convertDateToString(date: Option[Date]): Option[String] = {
    date.map(dv => new SimpleDateFormat(dateFormat).format(dv))
  }
}

/**
  * A class to transform data between the interface bus stop format and OTH internal bus stop format
  */
object TierekisteriBusStopMarshaller {

  private val liviIdPublicId = "yllapitajan_koodi"
  private val stopTypePublicId = "pysakin_tyyppi"
  private val nameFiPublicId = "nimi_suomeksi"
  private val nameSePublicId = "nimi_ruotsiksi"
  private val stopCode = "stop_code"
  private val InventoryDatePublicId = "inventointipaiva"
  private val FirstDayValidPublicId = "ensimmainen_voimassaolopaiva"
  private val LastDayValidPublicId = "viimeinen_voimassaolopaiva"
  private val expressPropertyValue = 4
  private val typeId: Int = 10
  private val dateFormat = "yyyy-MM-dd"

  private def convertDateToString(date: Option[Date]): Option[String] = {
    date.map(dv => new SimpleDateFormat(dateFormat).format(dv))
  }

  private def convertStringToDate(str: Option[String]): Option[Date] = {
    str.map(dv => new SimpleDateFormat(dateFormat).parse(dv))
  }

  def getAllPropertiesAvailable(AssetTypeId : Int): Seq[Property] = {
    Queries.availableProperties(AssetTypeId)
  }


  def getPropertyOption(propertyData: Seq[Property], publicId: String): Option[String] = {
    propertyData.find(p => p.publicId == publicId).flatMap(_.values.headOption.map(_.propertyValue))
  }
  def getPropertyDateOption(propertyData: Seq[Property], publicId: String): Option[Date] = {
    convertStringToDate(propertyData.find(p => p.publicId == publicId).flatMap(_.values.headOption.map(_.propertyValue)))
  }

  def toTRRoadSide(roadSide: RoadSide) = {
    roadSide match {
      case RoadSide.Right => TRRoadSide.Right
      case RoadSide.Left => TRRoadSide.Left
      case RoadSide.End => TRRoadSide.Off
      case _ => TRRoadSide.Unknown
    }
  }
  // TODO: Add the removal date here where applicable
  def toTierekisteriMassTransitStop(massTransitStop: PersistedMassTransitStop, roadAddress: RoadAddress, roadSideOption: Option[RoadSide]): TierekisteriMassTransitStop = {
    val inventoryDate = convertStringToDate(getPropertyOption(massTransitStop.propertyData, InventoryDatePublicId)).getOrElse(new Date)
    val startingDate = convertStringToDate(getPropertyOption(massTransitStop.propertyData, FirstDayValidPublicId))
    val lastDate = convertStringToDate(getPropertyOption(massTransitStop.propertyData, LastDayValidPublicId))
    TierekisteriMassTransitStop(massTransitStop.nationalId, findLiViId(massTransitStop.propertyData).getOrElse(""),
      roadAddress, roadSideOption.map(toTRRoadSide).getOrElse(TRRoadSide.Unknown), findStopType(massTransitStop.stopTypes),
      massTransitStop.stopTypes.contains(expressPropertyValue), mapEquipments(massTransitStop.propertyData),
      getPropertyOption(massTransitStop.propertyData, stopCode),
      getPropertyOption(massTransitStop.propertyData, nameFiPublicId),
      getPropertyOption(massTransitStop.propertyData, nameSePublicId),
      massTransitStop.modified.modifier.getOrElse(massTransitStop.created.modifier.get),
      startingDate, lastDate, None, inventoryDate)
  }

  // TODO: Map correctly, Seq(2) => local, Seq(2,3) => Combined, Seq(3) => Long distance
  private def findStopType(stopTypes: Seq[Int]): StopType = {
    // remove from OTH stoptypes the values that are not supported by tierekisteri
    val avaibleStopTypes = StopType.values.flatMap(_.propertyValues).intersect(stopTypes.toSet)
    //TODO try to improve that. Maybe just use a match
    val stopTypeOption = StopType.values.find(st => st.propertyValues.size == avaibleStopTypes.size && avaibleStopTypes.diff(st.propertyValues).isEmpty)

    stopTypeOption match {
      case Some(stopType) =>
        stopType
      case None =>
        StopType.Unknown
    }
  }

  private def mapEquipments(properties: Seq[Property]): Map[Equipment, Existence] = {
    properties.map(p => mapPropertyToEquipment(p) -> mapPropertyValueToExistence(p.values)).
      filterNot(p => p._1.equals(Equipment.Unknown)).toMap
  }

  private def mapPropertyToEquipment(p: Property) = {
    Equipment.fromPublicId(p.publicId)
  }

  private def mapPropertyValueToExistence(values: Seq[PropertyValue]) = {
    val v = values.map(pv => Existence.fromPropertyValue(pv.propertyValue)).distinct
    v.size match {
      case 1 => v.head
      case _ => Existence.Unknown // none or mismatching
    }
  }

  private def findLiViId(properties: Seq[Property]) = {
    properties.find(p =>
      p.publicId.equals(liviIdPublicId) && p.values.size > 0).map(_.values.head.propertyValue)
  }

  def fromTierekisteriMassTransitStop(massTransitStop: TierekisteriMassTransitStop): MassTransitStopWithProperties = {
    val allPropertiesAvailable = getAllPropertiesAvailable(typeId)

    val nationalId = massTransitStop.nationalId
    val stopTypes = massTransitStop.stopType.propertyValues

    val equipmentsProperty = mapEquipmentProperties(massTransitStop.equipments, allPropertiesAvailable)
    val stopTypeProperty = mapStopTypeProperties(massTransitStop.stopType, massTransitStop.express, allPropertiesAvailable)
    val liViIdProperty = mapLiViIdProperties(massTransitStop.liviId, allPropertiesAvailable)
    val nameFiProperty = mapNameFiProperties(massTransitStop.nameFi.getOrElse(""), allPropertiesAvailable)
    val nameSeProperty = mapNameSeProperties(massTransitStop.nameSe.getOrElse(""), allPropertiesAvailable)
    val allProperties = liViIdProperty++equipmentsProperty++stopTypeProperty++nameFiProperty++nameSeProperty

    MassTransitStopWithProperties(0L, nationalId, stopTypes.toSeq, 0.0, 0.0, None, None, None, floating = false, allProperties)
  }

  private def mapEquipmentProperties(equipments: Map[Equipment, Existence], allProperties: Seq[Property]): Seq[Property] = {
    equipments.map {
      case (equipment, existence) =>
        val equipmentProperties = allProperties.find(p => p.publicId.equals(equipment.publicId)).get
        Property(equipmentProperties.id, equipment.publicId, equipmentProperties.propertyType, equipmentProperties.required, Seq(PropertyValue(existence.propertyValue.toString)))
    }.toSeq
  }

  private def mapStopTypeProperties(stopType: StopType, isExpress: Boolean, allProperties: Seq[Property]): Seq[Property] = {
    var propertyValues = stopType.propertyValues.map { value =>
      PropertyValue(value.toString)
    }
    if (isExpress)
      propertyValues += PropertyValue(expressPropertyValue.toString)

    val stopTypeProperties = allProperties.find(p => p.publicId.equals(stopTypePublicId)).get

    Seq (Property(stopTypeProperties.id, stopTypePublicId, stopTypeProperties.propertyType, stopTypeProperties.required, propertyValues.toSeq))
  }

  private def mapLiViIdProperties(liViId: String, allProperties: Seq[Property]): Seq[Property] = {
    val liViIdProperties = allProperties.find(p => p.publicId.equals(liviIdPublicId)).get

    Seq(Property(liViIdProperties.id, liviIdPublicId, liViIdProperties.propertyType, liViIdProperties.required, Seq(PropertyValue(liViId))))
  }

  private def mapNameFiProperties(nameFi: String, allProperties: Seq[Property]): Seq[Property] = {
    val nameFiProperties = allProperties.find(p => p.publicId.equals(nameFiPublicId)).get

    Seq(Property(nameFiProperties.id, nameFiPublicId, nameFiProperties.propertyType, nameFiProperties.required, Seq(PropertyValue(nameFi))))
  }

  private def mapNameSeProperties(nameSe: String, allProperties: Seq[Property]): Seq[Property] = {
    val nameSeProperties = allProperties.find(p => p.publicId.equals(nameSePublicId)).get

    Seq(Property(nameSeProperties.id, nameSePublicId, nameSeProperties.propertyType, nameSeProperties.required, Seq(PropertyValue(nameSe))))
  }
}