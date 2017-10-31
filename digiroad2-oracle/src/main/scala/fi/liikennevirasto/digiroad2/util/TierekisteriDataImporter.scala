package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.linearasset.oracle.OracleLinearAssetDao
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.oracle.OracleAssetDao
import fi.liikennevirasto.digiroad2.roadaddress.oracle.RoadAddressDAO
import org.joda.time.DateTime
import fi.liikennevirasto.digiroad2.asset._


class TierekisteriDataImporter(vvhClient: VVHClient, oracleLinearAssetDao: OracleLinearAssetDao,
                               roadAddressDao: RoadAddressDAO, linearAssetService: LinearAssetService) {


  val roadLinkService = new RoadLinkService(vvhClient, new DummyEventBus, new DummySerializer)

  lazy val litRoadImporterOperations: LitRoadTierekisteriImporter = {
    new LitRoadTierekisteriImporter()
  }

  lazy val roadWidthImporterOperations: RoadWidthTierekisteriImporter = {
    new RoadWidthTierekisteriImporter()
  }

  lazy val trafficSignTierekisteriImporter: TrafficSignTierekisteriImporter = {
    new TrafficSignTierekisteriImporter()
  }

  lazy val pavedRoadImporterOperations: PavedRoadTierekisteriImporter = {
    new PavedRoadTierekisteriImporter()
  }

  lazy val massTransitLaneImporterOperations: MassTransitLaneTierekisteriImporter = {
    new MassTransitLaneTierekisteriImporter()
  }

  lazy val damagedByThawAssetImporterOperations: DamagedByThawTierekisteriImporter = {
    new DamagedByThawTierekisteriImporter()
  }

  lazy val europeanRoadImporterOperations: EuropeanRoadTierekisteriImporter = {
    new EuropeanRoadTierekisteriImporter()
  }

  lazy val speedLimitTierekisteriImporter: SpeedLimitsTierekisteriImporter = {
    new SpeedLimitsTierekisteriImporter()
  }

  lazy val speedLimitAssetTierekisteriImporter: SpeedLimitAssetTierekisteriImporter = {
    new SpeedLimitAssetTierekisteriImporter()
  }

  lazy val assetDao : OracleAssetDao = {
    new OracleAssetDao()
  }

  def obtainLastExecutionDate(assetName: String, assetId: Int): DateTime = {
    OracleDatabase.withDynSession{
      assetDao.getLastExecutionDate(assetId, s"batch_process_$assetName")
    }
  }

  def importTrafficVolumeAsset(tierekisteriTrafficVolumeAsset: TierekisteriTrafficVolumeAssetClient) = {
    println("\nExpiring Traffic Volume From OTH Database")
    OracleDatabase.withDynSession {
      oracleLinearAssetDao.expireAllAssetsByTypeId(TrafficVolume.typeId)
    }
    println("\nTraffic Volume data Expired")

    println("\nFetch Road Numbers From Viite")
    val roadNumbers = OracleDatabase.withDynSession {
      roadAddressDao.getRoadNumbers()
    }
    println("\nEnd of Fetch ")

    println("roadNumbers: ")
    roadNumbers.foreach(ra => println(ra))

    roadNumbers.foreach {
      case roadNumber =>
        println("\nFetch Traffic Volume by Road Number " + roadNumber)
        val trTrafficVolume = tierekisteriTrafficVolumeAsset.fetchActiveAssetData(roadNumber)

        trTrafficVolume.foreach { tr => println("\nTR: roadNumber, roadPartNumber, start, end and kvt " + tr.roadNumber + " " + tr.startRoadPartNumber + " " + tr.startAddressMValue + " " + tr.endAddressMValue + " " + tr.assetValue) }

        val r = trTrafficVolume.groupBy(trTrafficVolume => (trTrafficVolume.roadNumber, trTrafficVolume.startRoadPartNumber, trTrafficVolume.startAddressMValue, trTrafficVolume.endAddressMValue)).map(_._2.head)

        r.foreach { tr =>
          OracleDatabase.withDynTransaction {

            println("\nFetch road addresses to link ids using Viite, trRoadNumber, roadPartNumber start and end " + tr.roadNumber + " " + tr.startRoadPartNumber + " " + tr.startAddressMValue + " " + tr.endAddressMValue)
            val roadAddresses = roadAddressDao.getRoadAddressesFiltered(tr.roadNumber, tr.startRoadPartNumber, tr.startAddressMValue, tr.endAddressMValue)

            val roadAddressLinks = roadAddresses.map(ra => ra.linkId).toSet
            val vvhRoadlinks = roadLinkService.fetchVVHRoadlinks(roadAddressLinks)

            println("roadAddresses fetched: ")
            roadAddresses.filter(ra => vvhRoadlinks.exists(t => t.linkId == ra.linkId)).foreach(ra => println(ra.linkId))

            roadAddresses
              .filter(ra => vvhRoadlinks.exists(t => t.linkId == ra.linkId))
              .foreach { ra =>
                val assetId = linearAssetService.dao.createLinearAsset(TrafficVolume.typeId, ra.linkId, false, SideCode.BothDirections.value,
                  Measures(ra.startMValue, ra.endMValue), "batch_process_trafficVolume", vvhClient.createVVHTimeStamp(), Some(LinkGeomSource.NormalLinkInterface.value))
                println("\nCreated OTH traffic volume assets form TR data with assetId " + assetId)

                linearAssetService.dao.insertValue(assetId, LinearAssetTypes.numericValuePropertyId, tr.assetValue)
                println("\nCreated OTH property value with value " + tr.assetValue + " and assetId " + assetId)
              }
          }
        }
    }
    println("\nEnd of Traffic Volume fetch")
    println("\nEnd of creation OTH traffic volume assets form TR data")
  }

  def importLitRoadAsset(): Unit = {
    litRoadImporterOperations.importAssets()
  }

  def importRoadWidthAsset(): Unit = {
    roadWidthImporterOperations.importAssets()
  }

  def updateLitRoadAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(litRoadImporterOperations.assetName, LitRoad.typeId)
    litRoadImporterOperations.updateAssets(lastUpdate)
  }

  def updateRoadWidthAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(roadWidthImporterOperations.assetName, Width.typeId)
    roadWidthImporterOperations.updateAssets(lastUpdate)
  }

  def importTrafficSigns(): Unit = {
    trafficSignTierekisteriImporter.importAssets()
  }

  def updateTrafficSigns(): Unit = {
    val lastUpdate = obtainLastExecutionDate(trafficSignTierekisteriImporter.assetName, TrafficSigns.typeId)
    trafficSignTierekisteriImporter.updateAssets(lastUpdate)
  }

  def importSpeedLimits(): Unit = {
    speedLimitTierekisteriImporter.importAssets();
  }

  def importPavedRoadAsset(): Unit = {
    pavedRoadImporterOperations.importAssets()
  }

  def updatePavedRoadAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(pavedRoadImporterOperations.assetName, PavedRoad.typeId)
    pavedRoadImporterOperations.updateAssets(lastUpdate)
  }

  def importMassTransitLaneAsset(): Unit = {
    massTransitLaneImporterOperations.importAssets()
  }

  def updateMassTransitLaneAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(massTransitLaneImporterOperations.assetName, MassTransitLane.typeId)
    massTransitLaneImporterOperations.updateAssets(lastUpdate)
  }

  def importDamagedByThawAsset(): Unit = {
    damagedByThawAssetImporterOperations.importAssets()
  }

  def updateDamagedByThawAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(damagedByThawAssetImporterOperations.assetName, DamagedByThaw.typeId)
    damagedByThawAssetImporterOperations.updateAssets(lastUpdate)
  }

  def importEuropeanRoadAsset(): Unit = {
    europeanRoadImporterOperations.importAssets()
  }

  def updateEuropeanRoadAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(europeanRoadImporterOperations.assetName, EuropeanRoads.typeId)
    europeanRoadImporterOperations.updateAssets(lastUpdate)
  }

  def importSpeedLimitAsset(): Unit = {
    speedLimitAssetTierekisteriImporter.importAssets()
  }

  def updateSpeedLimitAssets(): Unit = {
    val lastUpdate = obtainLastExecutionDate(speedLimitAssetTierekisteriImporter.assetName, SpeedLimitAsset.typeId)
    speedLimitAssetTierekisteriImporter.updateAssets(lastUpdate)
  }
}
