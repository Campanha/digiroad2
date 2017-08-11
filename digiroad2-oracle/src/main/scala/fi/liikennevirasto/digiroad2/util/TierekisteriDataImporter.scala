package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode, State}
import fi.liikennevirasto.digiroad2.linearasset.oracle.OracleLinearAssetDao
import fi.liikennevirasto.digiroad2.masstransitstop.oracle.Queries
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.oracle.OracleAssetDao
import fi.liikennevirasto.digiroad2.roadaddress.oracle.RoadAddressDAO
import org.joda.time.DateTime


class TierekisteriDataImporter(vvhClient: VVHClient, oracleLinearAssetDao: OracleLinearAssetDao,
                               roadAddressDao: RoadAddressDAO, linearAssetService: LinearAssetService) {

  val trafficVolumeId = 170
  val litRoadAssetId = 100
  val roadWidthAssetId = 120
  val trafficSignsId = 300
  val pavedRoadAssetId = 110
  val massTransitLaneAssetId = 160
  val damagedByThawAssetId = 130

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

  lazy val damagedByThawAssetImporterOperations: DamagedByThawAssetTierekisteriImporter = {
    new DamagedByThawAssetTierekisteriImporter()
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
      oracleLinearAssetDao.expireAllAssetsByTypeId(trafficVolumeId)
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
                val assetId = linearAssetService.dao.createLinearAsset(trafficVolumeId, ra.linkId, false, SideCode.BothDirections.value,
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
    val lastUpdate = obtainLastExecutionDate(litRoadImporterOperations.assetName, litRoadAssetId)
    litRoadImporterOperations.updateAssets(lastUpdate)
  }

  def updateRoadWidthAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(roadWidthImporterOperations.assetName, roadWidthAssetId)
    roadWidthImporterOperations.updateAssets(lastUpdate)
  }

  def importTrafficSigns(): Unit = {
    trafficSignTierekisteriImporter.importAssets()
  }

  def updateTrafficSigns(): Unit = {
    val lastUpdate = obtainLastExecutionDate(trafficSignTierekisteriImporter.assetName, trafficSignsId)
    trafficSignTierekisteriImporter.updateAssets(lastUpdate)
  }

  def importPavedRoadAsset(): Unit = {
    pavedRoadImporterOperations.importAssets()
  }

  def updatePavedRoadAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(pavedRoadImporterOperations.assetName, pavedRoadAssetId)
    pavedRoadImporterOperations.updateAssets(lastUpdate)
  }

  def importMassTransitLaneAsset(): Unit = {
    massTransitLaneImporterOperations.importAssets()
  }

  def updateMassTransitLaneAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(massTransitLaneImporterOperations.assetName, massTransitLaneAssetId)
    massTransitLaneImporterOperations.updateAssets(lastUpdate)
  }

  def importDamagedByThawAsset(): Unit = {
    damagedByThawAssetImporterOperations.importAssets()
  }

  def updateDamagedByThawAsset(): Unit = {
    val lastUpdate = obtainLastExecutionDate(damagedByThawAssetImporterOperations.assetName, massTransitLaneAssetId)
    damagedByThawAssetImporterOperations.updateAssets(lastUpdate)
  }
}
