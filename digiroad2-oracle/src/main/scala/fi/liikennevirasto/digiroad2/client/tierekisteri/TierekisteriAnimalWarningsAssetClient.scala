package fi.liikennevirasto.digiroad2.client.tierekisteri

import fi.liikennevirasto.digiroad2.asset.AnimalWarnings
import fi.liikennevirasto.digiroad2.util.Track
import org.apache.http.impl.client.CloseableHttpClient

case class TierekisteriAnimalWarningsData(roadNumber: Long, startRoadPartNumber: Long, endRoadPartNumber: Long,
                                     track: Track, startAddressMValue: Long, endAddressMValue: Long, animalWarningValue: AnimalWarnings) extends TierekisteriAssetData

class TierekisteriAnimalWarningsAssetClient(trEndPoint: String, trEnable: Boolean, httpClient: CloseableHttpClient) extends TierekisteriAssetDataClient {
  override def tierekisteriRestApiEndPoint: String = trEndPoint
  override def tierekisteriEnabled: Boolean = trEnable
  override def client: CloseableHttpClient = httpClient
  type TierekisteriType = TierekisteriAnimalWarningsData

  override val trAssetType = "tl303"
  private val trAnimalWarningValue = "HIRVIVARO"

  override def mapFields(data: Map[String, Any]): Option[TierekisteriAnimalWarningsData] = {
    //Mandatory field
    val roadNumber = convertToLong(getMandatoryFieldValue(data, trRoadNumber)).get
    val roadPartNumber = convertToLong(getMandatoryFieldValue(data, trRoadPartNumber)).get
    val endRoadPartNumber = convertToLong(getMandatoryFieldValue(data, trEndRoadPartNumber)).getOrElse(roadPartNumber)
    val startMValue = convertToLong(getMandatoryFieldValue(data, trStartMValue)).get
    val endMValue = convertToLong(getMandatoryFieldValue(data, trEndMValue)).get
    val track = convertToInt(getMandatoryFieldValue(data, trTrackCode)).map(Track.apply).getOrElse(Track.Unknown)
    val animalWarningValue = convertToInt(getMandatoryFieldValue(data, trAnimalWarningValue)).get

    Some(TierekisteriAnimalWarningsData(roadNumber, roadPartNumber, endRoadPartNumber, track, startMValue, endMValue, AnimalWarnings.apply(animalWarningValue)))
  }
}

