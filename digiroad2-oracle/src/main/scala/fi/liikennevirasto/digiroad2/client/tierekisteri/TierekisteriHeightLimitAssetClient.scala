package fi.liikennevirasto.digiroad2.client.tierekisteri

import fi.liikennevirasto.digiroad2.util.{RoadSide, Track}
import org.apache.http.impl.client.CloseableHttpClient

case class TierekisteriHeightLimitData(roadNumber: Long, startRoadPartNumber: Long, endRoadPartNumber: Long,
                                       track: Track, startAddressMValue: Long, endAddressMValue: Long, roadSide: RoadSide, height: Int) extends TierekisteriAssetData

class TierekisteriHeightLimitAssetClient(trEndPoint: String, trEnable: Boolean, httpClient: CloseableHttpClient) extends TierekisteriAssetDataClient{
  override def tierekisteriRestApiEndPoint: String = trEndPoint
  override def tierekisteriEnabled: Boolean = trEnable
  override def client: CloseableHttpClient = httpClient
  type TierekisteriType = TierekisteriHeightLimitData

  override val trAssetType = "tl263"
  private val trHeight = "ALIKKO"
  private val trRoadSide = "PUOLI"

  override def mapFields(data: Map[String, Any]): Option[TierekisteriHeightLimitData] = {
    convertToInt(getFieldValue(data, trHeight)).map{
      heightValue =>

        //Mandatory field
        val roadNumber = convertToLong(getMandatoryFieldValue(data, trRoadNumber)).get
        val roadPartNumber = convertToLong(getMandatoryFieldValue(data, trRoadPartNumber)).get
        val startMValue = convertToLong(getMandatoryFieldValue(data, trStartMValue)).get
        val track = convertToInt(getMandatoryFieldValue(data, trTrackCode)).map(Track.apply).getOrElse(Track.Unknown)
        val roadSide = convertToInt(getMandatoryFieldValue(data, trRoadSide)).map(RoadSide.apply).getOrElse(RoadSide.Unknown)

        TierekisteriHeightLimitData(roadNumber, roadPartNumber, roadPartNumber, track, startMValue, startMValue, roadSide, heightValue)
    }
  }
}
