package fi.liikennevirasto.digiroad2.dataimport

import java.io.{ByteArrayInputStream, InputStream}

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.{PropertyValue, _}
import fi.liikennevirasto.digiroad2.client.tierekisteri.TierekisteriMassTransitStopClient
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHClient, VVHRoadLinkClient, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.{MassTransitStopDao, MunicipalityDao, OracleUserProvider}
import fi.liikennevirasto.digiroad2.dataimport.CsvImporter._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.pointasset.masstransitstop.{MassTransitStopService, MassTransitStopWithProperties, PersistedMassTransitStop}
import fi.liikennevirasto.digiroad2.user.{Configuration, User, UserProvider}
import fi.liikennevirasto.digiroad2.util.{GeometryTransform, RoadAddress, RoadSide, Track}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, Tag}

class CsvImporterSpec extends AuthenticatedApiSpec with BeforeAndAfter {
  val MunicipalityKauniainen = 235
  val testUserProvider = new OracleUserProvider
  val csvImporter = importerWithNullService()

  private def importerWithService(service: MassTransitStopService, testVVHClient: VVHClient) : CsvImporter = {
    new CsvImporter {
      override val massTransitStopService: MassTransitStopService = service
      override val userProvider: UserProvider = testUserProvider
      override val vvhClient: VVHClient = testVVHClient
    }
  }

  private def importerWithNullService() : CsvImporter = {
    new CsvImporter {
      override val massTransitStopService: MassTransitStopService = MockitoSugar.mock[MassTransitStopService]
      override val userProvider: UserProvider = testUserProvider
      override val vvhClient: VVHClient = MockitoSugar.mock[VVHClient]
    }
  }

  before {
    testUserProvider.setCurrentUser(User(id = 1, username = "CsvImportApiSpec", configuration = Configuration(authorizedMunicipalities = Set(MunicipalityKauniainen))))
  }

  test("rowToString works correctly for few basic fields") {
    csvImporter.rowToString(Map(
      "Valtakunnallinen ID" -> "ID",
      "Pysäkin nimi" -> "Nimi"
    )) should equal("Valtakunnallinen ID: 'ID', Pysäkin nimi: 'Nimi'")
  }

  val defaultKeys = "Valtakunnallinen ID" :: csvImporter.mappings.keys.toList

  val defaultValues = defaultKeys.map { key => key -> "" }.toMap

  private def createCSV(assets: Map[String, Any]*): String = {
    val headers = defaultKeys.mkString(";") + "\n"
    val rows = assets.map { asset =>
      defaultKeys.map { key => asset.getOrElse(key, "") }.mkString(";")
    }.mkString("\n")
    headers + rows
  }

  test("update name by CSV import", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]
    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))
    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(2l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(2, 2, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = createCSV(Map("Valtakunnallinen ID" -> 1, "Pysäkin nimi" -> "UpdatedAssetName"),
      Map("Valtakunnallinen ID" -> 2, "Pysäkin nimi" -> "Asset2Name"))

    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream)
    result should equal(ImportResult())

    val properties1 = Set(SimpleProperty("nimi_suomeksi", Seq(PropertyValue("UpdatedAssetName"))))
    val properties2 = Set(SimpleProperty("nimi_suomeksi", Seq(PropertyValue("Asset2Name"))))
    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties1), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
    verify(mockService).updateExistingById(ArgumentMatchers.eq(2l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties2), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("do not update name if field is empty in CSV", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = createCSV(Map("Valtakunnallinen ID" -> 1))

    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream)
    result should equal(ImportResult())

    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(Set.empty), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("validation fails if type is undefined", Tag("db")) {
    val assetFields = Map("Valtakunnallinen ID" -> 1, "Pysäkin tyyppi" -> ",")
    val invalidCsv = csvToInputStream(createCSV(assetFields))
    csvImporter.importAssets(invalidCsv) should equal(ImportResult(
      malformedAssets = List(MalformedAsset(
        malformedParameters = List("Pysäkin tyyppi"),
        csvRow = csvImporter.rowToString(defaultValues ++ assetFields)))))
  }

  test("validation fails if type contains illegal characters", Tag("db")) {
    val assetFields = Map("Valtakunnallinen ID" -> 1, "Pysäkin tyyppi" -> "2,a")
    val invalidCsv = csvToInputStream(createCSV(assetFields))
    csvImporter.importAssets(invalidCsv) should equal(ImportResult(
      malformedAssets = List(MalformedAsset(
        malformedParameters = List("Pysäkin tyyppi"),
        csvRow = csvImporter.rowToString(defaultValues ++ assetFields)))))
  }

  test("validation fails when asset type is unknown", Tag("db")) {
    val assetFields = Map("Valtakunnallinen ID" -> 1, "Pysäkin tyyppi" -> "2,10")
    val invalidCsv = csvToInputStream(createCSV(assetFields))
    csvImporter.importAssets(invalidCsv) should equal(ImportResult(
      malformedAssets = List(MalformedAsset(
        malformedParameters = List("Pysäkin tyyppi"),
        csvRow = csvImporter.rowToString(defaultValues ++ assetFields)))))
  }

  test("update asset type by CSV import", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = csvToInputStream(createCSV(Map("Valtakunnallinen ID" -> 1, "Pysäkin tyyppi" -> "1,2 , 3 ,4")))

    importer.importAssets(csv) should equal(ImportResult())

    val properties = Set(SimpleProperty("pysakin_tyyppi", Seq(PropertyValue("4"), PropertyValue("3"), PropertyValue("2"), PropertyValue("1"))))
    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("update asset admin id by CSV import", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = csvToInputStream(createCSV(Map("Valtakunnallinen ID" -> 1, "Ylläpitäjän tunnus" -> "NewAdminId")))

    importer.importAssets(csv) should equal(ImportResult())
    val properties = Set(SimpleProperty("yllapitajan_tunnus", Seq(PropertyValue("NewAdminId"))))
    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("Should not update asset LiVi id by CSV import (after Tierekisteri integration)", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = csvToInputStream(createCSV(Map("Valtakunnallinen ID" -> 1, "LiVi-tunnus" -> "Livi987654")))

    importer.importAssets(csv) should equal(ImportResult())
    val properties = Set(SimpleProperty("yllapitajan_koodi", Seq(PropertyValue("Livi987654")))).filterNot(_.publicId == "yllapitajan_koodi")
    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("update asset stop code by CSV import", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = csvToInputStream(createCSV(Map("Valtakunnallinen ID" -> 1, "Matkustajatunnus" -> "H156")))

    importer.importAssets(csv) should equal(ImportResult())
    val properties = Set(SimpleProperty("matkustajatunnus", Seq(PropertyValue("H156"))))
    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("update additional information by CSV import", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = csvToInputStream(createCSV(Map("Valtakunnallinen ID" -> 1, "Lisätiedot" -> "Updated additional info")))

    importer.importAssets(csv) should equal(ImportResult())
    val properties = Set(SimpleProperty("lisatiedot", Seq(PropertyValue("Updated additional info"))))
    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  val exampleValues = Map(
    "nimi_suomeksi" -> ("Passila", "Pasila"),
    "yllapitajan_tunnus" -> ("1234", "1281"),
    "yllapitajan_koodi" -> ("LiVV", "LiVi123"),
    "matkustajatunnus" -> ("sdjkal", "9877"),
    "pysakin_tyyppi" -> ("2", "1"),
    "nimi_ruotsiksi" -> ("Bölle", "Böle"),
    "liikennointisuunta" -> ("Itään", "Pohjoiseen"),
    "katos" -> ("1", "2"),
    "aikataulu" -> ("1", "2"),
    "mainoskatos" -> ("1", "2"),
    "penkki" -> ("1", "2"),
    "pyorateline" -> ("1", "2"),
    "sahkoinen_aikataulunaytto" -> ("1", "2"),
    "valaistus" -> ("1", "2"),
    "saattomahdollisuus_henkiloautolla" -> ("1", "2"),
    "lisatiedot" -> ("qwer", "asdf"),
    "tietojen_yllapitaja" -> ("1", "3"),
    "korotettu" -> ("1", "2"),
    "roska_astia" -> ("1", "2"),
    "vyohyketieto" -> ("InfoZone", "InfoZone ready")
  )

  test("update asset's properties in a generic manner", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = csvToInputStream(createCSV(Map("Valtakunnallinen ID" -> 1) ++ csvImporter.mappings.mapValues(exampleValues(_)._2)))

    importer.importAssets(csv) should equal(ImportResult())
    val properties: Set[SimpleProperty] = exampleValues.map { case (key, value) =>
      SimpleProperty(key, Seq(PropertyValue(value._2)))
    }.filterNot(_.publicId == "yllapitajan_koodi").toSet

    verify(mockService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("raise an error when updating non-existent asset", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(6l), anyObject())).thenReturn(None)
    val assetFields = Map("Valtakunnallinen ID" -> "6", "Pysäkin nimi" -> "AssetName")

    val importer = importerWithService(mockService, mockVVHClient)
    val csv = createCSV(assetFields)

    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream)

    result should equal(ImportResult(nonExistingAssets = List(NonExistingAsset(externalId = 6, csvRow = importer.rowToString(defaultValues ++ assetFields)))))
  }

  test("raise an error when csv row does not define required parameter", Tag("db")) {
    val mockService = MockitoSugar.mock[MassTransitStopService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]

    when(mockService.getMassTransitStopByNationalId(ArgumentMatchers.eq(1l), anyObject())).thenReturn(Some(MassTransitStopWithProperties(1, 1, Nil, 0.0, 0.0, None, None, None, false, Nil)))

    val importer = importerWithService(mockService, mockVVHClient)
    val missingRequiredKeys = defaultKeys.filterNot(Set("Pysäkin nimi"))
    val csv =
      missingRequiredKeys.mkString(";") + "\n" +
        s"${1}" + missingRequiredKeys.map(_ => ";").mkString + "\n"
    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream)

    result should equal(ImportResult(
      incompleteAssets = List(IncompleteAsset(missingParameters = List("Pysäkin nimi"), csvRow = importer.rowToString(defaultValues - "Pysäkin nimi" ++ Map("Valtakunnallinen ID" -> 1))))))

    verify(mockService, never).updateExistingById(anyLong(), anyObject(), anyObject(), anyString(), anyObject())
  }

  private def mockWithMassTransitStops(stops: Seq[(Long, AdministrativeClass)]): (MassTransitStopService, VVHClient) = {

    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val mockVVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
    val mockTierekisteriClient = MockitoSugar.mock[TierekisteriMassTransitStopClient]

    when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
    stops.foreach { case(id, administrativeClass) =>
      when(mockVVHRoadLinkClient.fetchByLinkId(ArgumentMatchers.eq(id))).thenReturn(Some(VVHRoadlink(id, 235, Nil, administrativeClass, TrafficDirection.BothDirections, FeatureClass.AllOthers)))
    }

    val mockMassTransitStopDao = MockitoSugar.mock[MassTransitStopDao]
    val mockMunicipalityDao = MockitoSugar.mock[MunicipalityDao]
    val mockGeometryTransform = MockitoSugar.mock[GeometryTransform]
    when(mockMassTransitStopDao.getAssetAdministrationClass(any[Long])).thenReturn(None)

    class TestMassTransitStopService(val eventbus: DigiroadEventBus, val roadLinkService: RoadLinkService) extends MassTransitStopService {
      override def withDynSession[T](f: => T): T = f
      override def withDynTransaction[T](f: => T): T = f
      override val tierekisteriClient: TierekisteriMassTransitStopClient = mockTierekisteriClient
      override val massTransitStopDao: MassTransitStopDao = mockMassTransitStopDao
      override val municipalityDao: MunicipalityDao = mockMunicipalityDao
      override val geometryTransform: GeometryTransform = mockGeometryTransform
    }

    when(mockGeometryTransform.resolveAddressAndLocation(any[Point], any[Int], any[Double], any[Long], any[Int], any[Option[Int]], any[Option[Int]])).thenReturn((RoadAddress(None, 0, 0, Track.Unknown, 0, None) , RoadSide.Right))
    val mockMassTransitStopService = MockitoSugar.mock[MassTransitStopService]
    stops.foreach { case (id, administrativeClass) =>
      when(mockMassTransitStopService.getByNationalId(ArgumentMatchers.eq(id), anyObject(), anyObject())).thenAnswer(new Answer[Option[Object]] {
        override def answer(invocation: InvocationOnMock): Option[Object] = {
          val transformation: PersistedMassTransitStop => (Object, Object) = invocation.getArguments()(2).asInstanceOf[PersistedMassTransitStop => (Object, Object)]
          val stop = PersistedMassTransitStop(id, id, id, Nil, 235, 0.0, 0.0, 0.0, None, None, None, false, 0, Modification(None, None), Modification(None, None), Nil, NormalLinkInterface)
          Some(transformation(stop)._1)
        }
      })
    }

    when(mockMassTransitStopService.isFloating(any[PersistedPointAsset], any[Option[VVHRoadlink]])).thenAnswer(new Answer[Object] {
      override def answer(invocation: InvocationOnMock): Object = {
        val persistedPointAsset: PersistedPointAsset  = invocation.getArguments()(0).asInstanceOf[PersistedPointAsset]
        val vvhRoadlink: Option[VVHRoadlink]  = invocation.getArguments()(1).asInstanceOf[Option[VVHRoadlink]]

        val testMassTransitStopService = new TestMassTransitStopService(new DummyEventBus, MockitoSugar.mock[RoadLinkService])
        testMassTransitStopService.isFloating(persistedPointAsset, vvhRoadlink)
      }
    })

    (mockMassTransitStopService, mockVVHClient)
  }

  test("ignore updates on other road types than streets when import is limited to streets") {
    val (mockMassTransitStopService, mockVVHClient) = mockWithMassTransitStops(Seq((1l, State)))
    val importer = importerWithService(mockMassTransitStopService, mockVVHClient)
    val assetFields = Map("Valtakunnallinen ID" -> 1, "Pysäkin nimi" -> "NewName")

    val csv = createCSV(assetFields)
    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream, roadTypeLimitations = Set(Municipality))

    result should equal(ImportResult(
      excludedAssets = List(ExcludedAsset(affectedRoadLinkType = "State", csvRow = importer.rowToString(defaultValues ++ assetFields)))))

    verify(mockMassTransitStopService, never).updateExistingById(anyLong(), anyObject(), anyObject(), anyString(), anyObject())
  }

  test("update asset on street when import is limited to streets") {
    val (mockMassTransitStopService, mockVVHClient) = mockWithMassTransitStops(Seq((1l, Municipality)))
    val importer = importerWithService(mockMassTransitStopService, mockVVHClient)

    val csv = createCSV(Map("Valtakunnallinen ID" -> 1, "Pysäkin nimi" -> "NewName"))
    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream, roadTypeLimitations = Set(Municipality))
    result should equal(ImportResult())

    val properties = Set(SimpleProperty("nimi_suomeksi", Seq(PropertyValue("NewName"))))
    verify(mockMassTransitStopService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("update asset on roads and streets when import is limited to roads and streets") {
    val (mockMassTransitStopService, mockVVHClient) = mockWithMassTransitStops(Seq((1l, Municipality), (2l, State)))
    val importer = importerWithService(mockMassTransitStopService, mockVVHClient)

    val csv = createCSV(Map("Valtakunnallinen ID" -> 1, "Pysäkin nimi" -> "NewName1"), Map("Valtakunnallinen ID" -> 2, "Pysäkin nimi" -> "NewName2"))
    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream, roadTypeLimitations = Set(State, Municipality))
    result should equal(ImportResult())

    val properties1 = Set(SimpleProperty("nimi_suomeksi", Seq(PropertyValue("NewName1"))))
    val properties2 = Set(SimpleProperty("nimi_suomeksi", Seq(PropertyValue("NewName2"))))
    verify(mockMassTransitStopService).updateExistingById(ArgumentMatchers.eq(1l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties1), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
    verify(mockMassTransitStopService).updateExistingById(ArgumentMatchers.eq(2l), ArgumentMatchers.eq(None), ArgumentMatchers.eq(properties2), ArgumentMatchers.eq("CsvImportApiSpec"), anyObject())
  }

  test("ignore updates on all other road types than private roads when import is limited to private roads") {
    val (mockMassTransitStopService, mockVVHClient) = mockWithMassTransitStops(Seq((1l, Municipality), (2l, State)))
    val importer = importerWithService(mockMassTransitStopService, mockVVHClient)

    val assetOnStreetFields = Map("Valtakunnallinen ID" -> 1, "Pysäkin nimi" -> "NewName1")
    val assetOnRoadFields = Map("Valtakunnallinen ID" -> 2, "Pysäkin nimi" -> "NewName2")
    val csv = createCSV(assetOnStreetFields, assetOnRoadFields)

    val inputStream = new ByteArrayInputStream(csv.getBytes)
    val result = importer.importAssets(inputStream, roadTypeLimitations = Set(Private))
    result should equal(ImportResult(
      excludedAssets = List(ExcludedAsset(affectedRoadLinkType = "State", csvRow = csvImporter.rowToString(defaultValues ++ assetOnRoadFields)),
        ExcludedAsset(affectedRoadLinkType = "Municipality", csvRow = csvImporter.rowToString(defaultValues ++ assetOnStreetFields)))))

    verify(mockMassTransitStopService, never).updateExistingById(anyLong(), anyObject(), anyObject(), anyString(), anyObject())
  }

  private def csvToInputStream(csv: String): InputStream = new ByteArrayInputStream(csv.getBytes())
}
