package fi.liikennevirasto.digiroad2.client.tierekisteri

import fi.liikennevirasto.digiroad2.util.Track
import org.apache.http.impl.client.CloseableHttpClient

case class TierekisteriWeightLimitData(roadNumber: Long, startRoadPartNumber: Long, endRoadPartNumber: Long,
                                       track: Track, startAddressMValue: Long, endAddressMValue: Long,
                                       totalWeight: Option[Int], trailerTruckWeight: Option[Int], axleWeight: Option[Int], bogieWeight: Option[Int], threeBogieWeight: Option[Int]) extends TierekisteriAssetData

class TierekisteriWeightLimitAssetClient(trEndPoint: String, trEnable: Boolean, httpClient: CloseableHttpClient) extends TierekisteriAssetDataClient{
  override def tierekisteriRestApiEndPoint: String = trEndPoint
  override def tierekisteriEnabled: Boolean = trEnable
  override def client: CloseableHttpClient = httpClient
  type TierekisteriType = TierekisteriWeightLimitData

  override val trAssetType = "tl261"
  private val trTotalWeight = "AJONPAINO"
  private val trTrailerTruckWeight = "YHDPAINO"
  private val trAxleWeight = "AKSPAINO"
  private val trBogieWeight = "TELIPAINO"
  private val trThreeBogieWeight = "TELIPAINO3"


  override def mapFields(data: Map[String, Any]): Option[TierekisteriWeightLimitData] = {
    val trTotalWeightValue = convertToInt(getFieldValue(data, trTotalWeight))
    val trTrailerTruckWeightValue = convertToInt(getFieldValue(data, trTrailerTruckWeight))
    val trAxleWeightValue = convertToInt(getFieldValue(data, trAxleWeight))
    val trBogieWeightValue = convertToInt(getFieldValue(data, trBogieWeight))
    val trThreeBogieWeightValue = convertToInt(getFieldValue(data, trThreeBogieWeight))

    //Mandatory field
    val roadNumber = convertToLong(getMandatoryFieldValue(data, trRoadNumber)).get
    val roadPartNumber = convertToLong(getMandatoryFieldValue(data, trRoadPartNumber)).get
    val startMValue = convertToLong(getMandatoryFieldValue(data, trStartMValue)).get
    val track = convertToInt(getMandatoryFieldValue(data, trTrackCode)).map(Track.apply).getOrElse(Track.Unknown)

    Some(TierekisteriWeightLimitData(roadNumber, roadPartNumber, roadPartNumber, track, startMValue, startMValue,
      trTotalWeightValue, trTrailerTruckWeightValue, trAxleWeightValue, trBogieWeightValue, trThreeBogieWeightValue))
     }
}
