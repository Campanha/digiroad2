package fi.liikennevirasto.digiroad2.process

import fi.liikennevirasto.digiroad2.{FreeHeight, MaxHeightExceeding, TrafficSignType}
import fi.liikennevirasto.digiroad2.asset.{AssetTypeInfo, HeightLimit, TextPropertyValue}
import fi.liikennevirasto.digiroad2.dao.pointasset.PersistedTrafficSign
import fi.liikennevirasto.digiroad2.linearasset.PersistedLinearAsset


class HeightLimitValidator extends SevenRestrictionsLimitationValidator {
  override def assetTypeInfo: AssetTypeInfo = HeightLimit
  override val allowedTrafficSign: Set[TrafficSignType] = Set(FreeHeight, MaxHeightExceeding)
  override val radiusDistance: Int = 50

  override def comparingAssetAndTrafficValue(asset: PersistedLinearAsset, trafficSign: PersistedTrafficSign): Boolean = {
    TrafficSignType.applyOTHValue(trafficSignService.getProperty(trafficSign, "trafficSigns_type").get.propertyValue.toInt) match {
      case FreeHeight =>
        trafficSignService.getProperty(trafficSign, "trafficSigns_info").getOrElse(TextPropertyValue("")).propertyValue == getAssetValue(asset)
      case MaxHeightExceeding =>
        trafficSignService.getProperty(trafficSign, "trafficSigns_value").getOrElse(TextPropertyValue("")).propertyValue == getAssetValue(asset)
      case _ => throw new NumberFormatException(s"Not supported trafficSign on ${assetTypeInfo.label} asset")
    }
  }
}

