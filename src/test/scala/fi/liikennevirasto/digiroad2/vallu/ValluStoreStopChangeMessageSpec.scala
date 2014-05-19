package fi.liikennevirasto.digiroad2.vallu

import org.scalatest._
import fi.liikennevirasto.digiroad2.asset.{PropertyValue, PropertyTypes, Property, AssetWithProperties}
import scala.xml.{Elem, NodeSeq, XML}
import javax.xml.validation.SchemaFactory
import javax.xml.XMLConstants
import java.io.{StringReader}
import javax.xml.transform.stream.StreamSource
import fi.liikennevirasto.digiroad2.asset.Modification
import org.joda.time.DateTime

class ValluStoreStopChangeMessageSpec extends FlatSpec with MustMatchers {
  val modifiedDatetime = new DateTime(2014, 5, 19, 10, 21, 0)
  val createdDateTime = modifiedDatetime.minusYears(1).minusMonths(1).minusDays(1)

  val testAsset = AssetWithProperties(
    id = 1,
    externalId = Some(123),
    assetTypeId = 1,
    validityDirection = Some(2),
    lon = 1,
    lat = 1,
    roadLinkId = 1,
    wgslon = 1,
    wgslat = 1,
    bearing = Some(120),
    municipalityNumber = Some(235),
    created = Modification(Some(createdDateTime), Some("creator")),
    modified = Modification(Some(modifiedDatetime), Some("testUser")),
    propertyData = List(
        Property(id = 1, publicId = "tietojen_yllapitaja", propertyType = "text", values = List(PropertyValue("1", Some("Ei tiedossa"))))
    )
  )

  it must "specify encoding" in {
    val message: String = ValluStoreStopChangeMessage.create("Kauniainen", testAsset)
    validateValluMessage(message)
    message startsWith("""<?xml version="1.0" encoding="UTF-8"?>""")
  }

  it must "exclude optional elements" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    (xml \ "AdminStopId").text must equal("")
    (xml \ "StopCode").text must equal("")
    (xml \ "Names" ) must be ('empty)
    (xml \ "SpecialNeeds").text must equal("")
    (xml \ "Comments").text must equal("")
  }

  it must "specify external id" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    (xml \ "StopId").text must equal("123")
  }

  it must "specify xCoordinate" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    val xCoordinate = xml \ "Coordinate" \ "xCoordinate"
    xCoordinate.text.toDouble must equal(1.0)
  }

  it must "specify yCoordinate" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    val yCoordinate = xml \ "Coordinate" \ "yCoordinate"
    yCoordinate.text.toDouble must equal(1.0)
  }

  it must "specify bearing" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    (xml \ "Bearing").text must equal("120")
  }

  it must "specify bearing description" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("liikennointisuuntima", "Itä"))))
    (xml \ "BearingDescription").text must equal("Itä")
  }

  it must "specify direction" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("liikennointisuunta", "johonkin"))))
    (xml \ "Direction").text must equal("johonkin")
  }

  it must "specify special needs" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("esteettomyys_liikuntarajoitteiselle", "Tuoli"))))
    (xml \ "SpecialNeeds").text must equal("Tuoli")
  }

  it must "specify reachability" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(
      ("saattomahdollisuus_henkiloautolla", "2"),
      ("liityntapysakointipaikkojen_maara", "10"),
      ("liityntapysakoinnin_lisatiedot", "sähköautoille"))))
    (xml \ "Reachability").text must equal("Liityntäpysäköinti, 10 pysäköintipaikkaa, sähköautoille")
  }

  it must "specify reachability without continuous parking" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(
      ("saattomahdollisuus_henkiloautolla", "86"),
      ("liityntapysakointipaikkojen_maara", "10"),
      ("liityntapysakoinnin_lisatiedot", "sähköautoille"))))
    (xml \ "Reachability").text must equal("10 pysäköintipaikkaa, sähköautoille")
  }

  it must "specify reachability without continuous parking and parking space" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(
      ("liityntapysakoinnin_lisatiedot", "sähköautoille"))))
    (xml \ "Reachability").text must equal("sähköautoille")
  }

  it must "specify modified by" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    (xml \ "ModifiedBy").text must equal("testUser")
  }

  it must "specify modified timestamp" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    (xml \ "ModifiedTimestamp").text must equal("2014-05-19T10:21:00")
  }

  it must "specify modified by when creating new bus stop" in {
    val xml = validateAndParseTestAssetMessage(testAsset.copy(modified = new Modification(None, None)))
    (xml \ "ModifiedBy").text must equal("creator")
  }

  it must "specify modified timestamp when creating new bus stop" in {
    val xml = validateAndParseTestAssetMessage(testAsset.copy(modified = new Modification(None, None)))
    (xml \ "ModifiedTimestamp").text must equal("2013-04-18T10:21:00")
  }

  it must "specify administrator  code" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("tietojen_yllapitaja", "Ei tiedossa"))))
    (xml \ "AdministratorCode").text must equal("Ei tiedossa")
  }

  it must "specify municipality code" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    (xml \ "MunicipalityCode").text must equal("235")
  }

  it must "specify municipality name" in {
    val xml = validateAndParseTestAssetMessage(testAsset)
    (xml \ "MunicipalityName").text must equal("Kauniainen")
  }

  it must "specify comments" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("lisatiedot", "No comments"))))
    (xml \ "Comments").text must equal("No comments")
  }

  it must "specify administrator stop id" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("yllapitajan_tunnus", "Livi83857"))))
    (xml \ "AdminStopId").text must equal("Livi83857")
  }

  it must "specify stop code for stop" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("matkustajatunnus", "Poliisilaitos"))))
    (xml \ "StopCode").text must equal("Poliisilaitos")
  }

  it must "specify stop name in Finnish and Swedish" in {
    val stopElement = validateAndParseTestAssetMessage(testAssetWithProperties(List(("nimi_suomeksi", "Puutarhatie"), ("nimi_ruotsiksi", "Trädgårdvägen"))))
    val nameElements = stopElement \ "Names" \ "Name"
    val nameFi = nameElements filter { _ \ "@lang" exists(_.text == "fi") }
    val nameSv = nameElements filter { _ \ "@lang" exists(_.text == "sv") }
    nameFi.text must equal("Puutarhatie")
    nameSv.text must equal("Trädgårdvägen")
  }

  it must "specify only Finnish name" in {
    val stopElement = validateAndParseTestAssetMessage(testAssetWithProperties(List(("nimi_suomeksi", "Puutarhatie"))))
    val nameElements = stopElement \ "Names" \ "Name"
    val nameFi = nameElements filter { _ \ "@lang" exists(_.text == "fi") }
    val nameSv = nameElements filter { _ \ "@lang" exists(_.text == "sv") }
    nameFi.text must equal("Puutarhatie")
    nameSv must be('empty)
  }

  it must "specify validity start period" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("ensimmainen_voimassaolopaiva", "2014-05-21"))))
    (xml \ "ValidFrom").text must equal("2014-05-21T00:00:00")
  }

  it must "specify nil validity start period if not present" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("ensimmainen_voimassaolopaiva", ""))))
    val validFrom = (xml \ "ValidFrom").head
    validFrom.attribute("http://www.w3.org/2001/XMLSchema-instance", "nil").get.text must equal("true")
  }

  it must "specify nil validity end period if not present" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("viimeinen_voimassaolopaiva", ""))))
    val validFrom = (xml \ "ValidTo").head
    validFrom.attribute("http://www.w3.org/2001/XMLSchema-instance", "nil").get.text must equal("true")
  }

  it must "specify validity end period" in {
    val xml = validateAndParseTestAssetMessage(testAssetWithProperties(List(("viimeinen_voimassaolopaiva", "2014-05-21"))))
    (xml \ "ValidTo").text must equal("2014-05-21T00:00:00")
  }

  private def validateValluMessage(valluMessage: String) = {
    val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
    val source = new StreamSource(getClass.getResourceAsStream("/StopChange.xsd"))
    val schema = schemaFactory.newSchema(source)
    schema.newValidator().validate(new StreamSource(new StringReader(valluMessage)))
  }

  private def validateAndParseTestAssetMessage(asset: AssetWithProperties): NodeSeq = {
    val message = ValluStoreStopChangeMessage.create("Kauniainen", asset)
    validateValluMessage(message)
    XML.loadString(message) \ "Stop"
  }

  private def testAssetWithProperties(properties: List[(String, String)]) = {
    testAsset.copy(propertyData = properties.map { property =>
      Property(
        id = 1,
        publicId = property._1,
        propertyType = PropertyTypes.Text,
        values = List(PropertyValue(property._2, Some(property._2))))
    }.toSeq ++ testAsset.propertyData)
  }
}
