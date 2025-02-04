package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.service.pointasset.IncomingObstacle
import org.joda.time.LocalDate

/**
  * Created by venholat on 26.4.2016.
  */
object FloatingObstacleTestData {
  def generateTestData = {
    List(
      IncomingObstacle(531872.33,6992468.9245,5170496,1),
      IncomingObstacle(536046.9342,6994938.7178,0,1),
      IncomingObstacle(529031.0618,6992486.5783,0,1),
      IncomingObstacle(537344.8769,6983619.3833,0,1),
      IncomingObstacle(528676.261545953,6994794.58188387,0,1),
      IncomingObstacle(540553.1203,6995907.9355,0,1),
      IncomingObstacle(535592.468020477,7004094.02187679,0,1),
      IncomingObstacle(537564.4758,6997935.3009,6562265,1),
      IncomingObstacle(540893.2803,6984451.9629,0,1),
      IncomingObstacle(533440.7674,6984432.2654,0,1),
      IncomingObstacle(542110.6139,6996646.8449,6476769,1),
      IncomingObstacle(532786.5065,6996744.5463,0,1),
      IncomingObstacle(534140.7586,6989529.2517,5172071,1),
      IncomingObstacle(531511.598976562,6982765.72568624,0,1),
      IncomingObstacle(530290.6661,6994500.4441,5169976,1),
      IncomingObstacle(536591.503756139,7002605.0828529,0,1),
      IncomingObstacle(539637.2411,7003709.8303,6461012,1),
      IncomingObstacle(536705.6921,6983768.6734,0,1),
      IncomingObstacle(540859.6852,6984902.0179,0,1),
      IncomingObstacle(520152.7526,6990756.9408,6460784,1),
      IncomingObstacle(534554.9034,6994417.233,5169254,1),
      IncomingObstacle(535951.4849,7000725.0646,0,1),
      IncomingObstacle(540319.9159,6985375.4319,0,1),
      IncomingObstacle(532597.732155549,6988316.09620726,0,1),
      IncomingObstacle(540295.1831,6985569.0568,6562376,1),
      IncomingObstacle(536581.6049,7002610.2828,0,1),
      IncomingObstacle(529113.6328,6994583.2551,0,1),
      IncomingObstacle(528578.375,6986756.6328,0,1),
      IncomingObstacle(526593.592239834,6995135.59154519,5167652,1),
      IncomingObstacle(534856.7071,6996036.5761,0,1),
      IncomingObstacle(530267.9128,6986330.2607,5172986,1),
      IncomingObstacle(535681.4302,6996741.1079,0,1),
      IncomingObstacle(526590.853,6995138.2768,0,1),
      IncomingObstacle(539142.2088,6983795.0793,5177421,1),
      IncomingObstacle(535620.5352,7004115.1285,6460506,1),
      IncomingObstacle(528660.4322,6993699.1113,0,1),
      IncomingObstacle(530940.2843,6994671.675,0,1),
      IncomingObstacle(528606.0263,6986765.9908,6476703,1),
      IncomingObstacle(539514.078,6988029.6644,5177192,1),
      IncomingObstacle(529274.476935446,7003552.97760204,0,1),
      IncomingObstacle(540387.895071147,6987493.66823027,0,1),
      IncomingObstacle(540400.7186,6987506.595,0,1),
      IncomingObstacle(531601.9326,6987198.632,6561906,1),
      IncomingObstacle(536958.602764297,6996703.42939722,0,1),
      IncomingObstacle(536954.0277,6996719.8302,0,1),
      IncomingObstacle(530868.9907,6985627.3975,0,1),
      IncomingObstacle(533440.7674,6984432.2654,5175335,1),
      IncomingObstacle(540309.2948,6985731.9676,5177028,1),
      IncomingObstacle(527242.4132,6995359.72,6476593,1),
      IncomingObstacle(539231.093,6989646.3531,0,1),
      IncomingObstacle(535439.8307,6988465.4323,5172346,1),
      IncomingObstacle(530941.8993,6987064.5839,5172927,1),
      IncomingObstacle(530948.245,6982994.0135,0,1),
      IncomingObstacle(542061.887236297,6996631.53123311,0,1),
      IncomingObstacle(537354.5992,6992357.7855,5176885,1),
      IncomingObstacle(527884.677678039,6994338.32065866,0,1),
      IncomingObstacle(537165.7446,7002414.4329,0,1),
      IncomingObstacle(535396.417694237,7006024.36834416,0,1),
      IncomingObstacle(535016.6568,6988491.9512,0,1),
      IncomingObstacle(535307.9416,6985580.4783,0,1),
      IncomingObstacle(539798.3393,6987334.719,5177202,1),
      IncomingObstacle(540319.9159,6985375.4319,5177052,1),
      IncomingObstacle(537857.8633,6984594.6811,6562429,1),
      IncomingObstacle(539512.7628,6987905.2428,5177194,1),
      IncomingObstacle(530188.3502,6993857.7565,5170139,1),
      IncomingObstacle(540870.5847,6984759.0504,0,1),
      IncomingObstacle(533882.3122,6996021.994,6476663,1),
      IncomingObstacle(536115.8528,6982793.1609,0,1),
      IncomingObstacle(537929.8992,6996544.6926,0,1),
      IncomingObstacle(537905.2612,6996006.4135,5176762,1),
      IncomingObstacle(539348.3938,6988395.718,5177188,1),
      IncomingObstacle(530065.7195,7001638.6987,0,1),
      IncomingObstacle(532788.2139,6997100.4959,6476654,1),
      IncomingObstacle(537182.8004,7002474.9081,0,1),
      IncomingObstacle(530457.5808,6986238.0002,0,1),
      IncomingObstacle(537359.284,6992340.9905,5176883,1),
      IncomingObstacle(527781.178,7005732.5305,0,1),
      IncomingObstacle(533858.7775,6987563.8981,5172704,1),
      IncomingObstacle(536238.8618,6982654.6937,0,1),
      IncomingObstacle(532076.2157,6998962.8438,5168025,1),
      IncomingObstacle(535391.1847,7006062.8363,0,1),
      IncomingObstacle(536265.3229,7000412.9915,6561277,1),
      IncomingObstacle(533462.1138,6992719.7643,0,1),
      IncomingObstacle(539303.938,6990601.8891,5176848,1),
      IncomingObstacle(539648.963089453,7003713.11097885,0,1),
      IncomingObstacle(535422.1721,6990875.6639,0,1),
      IncomingObstacle(537244.3705,7006822.9167,0,1),
      IncomingObstacle(536094.0243,6999098.5231,0,1),
      IncomingObstacle(537524.0748,6992386.8985,5176882,1),
      IncomingObstacle(539512.7628,6987905.2428,0,1),
      IncomingObstacle(520182.4353,6990754.1177,0,1),
      IncomingObstacle(536544.2781,7009299.5742,6460376,1),
      IncomingObstacle(537276.7694,7000094.0701,6476642,1),
      IncomingObstacle(531114.81001527,6988514.19181074,0,1),
      IncomingObstacle(537201.0049,6982467.5659,5177539,1),
      IncomingObstacle(537042.9811,6996927.8703,0,1),
      IncomingObstacle(539285.9294,6989869.6566,0,1),
      IncomingObstacle(530941.8993,6987064.5839,0,1),
      IncomingObstacle(536084.4213,6995734.5043,5168813,1),
      IncomingObstacle(528222.7455,6994671.9376,0,1),
      IncomingObstacle(539285.9294,6989869.6566,5177158,1),
      IncomingObstacle(535477.9065,7006417.6918,0,1),
      IncomingObstacle(535477.9065,7006417.6918,0,1),
      IncomingObstacle(525856.8212,7000036.3077,0,1),
      IncomingObstacle(532161.9725,6992105.1094,0,1),
      IncomingObstacle(534678.1384,6983908.0453,0,1),
      IncomingObstacle(536305.2642,6995885.974,5176726,1),
      IncomingObstacle(525226.2536,7000218.9452,0,1),
      IncomingObstacle(532249.2761,6992373.1787,0,1),
      IncomingObstacle(531115.2938,6988521.6753,5172911,1),
      IncomingObstacle(530940.2843,6994671.675,5169862,1),
      IncomingObstacle(530900.0965,6985366.0586,0,1),
      IncomingObstacle(527154.07114212,6995185.09368879,6476594,1),
      IncomingObstacle(539265.2276,6989620.1467,5177167,1),
      IncomingObstacle(528050.9979,6991568.461,5170760,1),
      IncomingObstacle(537799.6939,6982797.4332,0,1),
      IncomingObstacle(537276.7694,7000094.0701,0,1),
      IncomingObstacle(536400.3586,6996023.3062,0,1),
      IncomingObstacle(530341.7959,6994575.3013,0,1),
      IncomingObstacle(538578.1075,7003283.0044,0,1),
      IncomingObstacle(531828.0253,6987127.6882,0,1),
      IncomingObstacle(529285.2642,7003555.9775,6460723,1),
      IncomingObstacle(536374.428,6994842.9038,5168816,1),
      IncomingObstacle(538573.354,7006506.5511,0,1),
      IncomingObstacle(532073.719859664,6998961.1788,0,1),
      IncomingObstacle(537042.9811,6996927.8703,6467790,1),
      IncomingObstacle(529113.6635,6999195.0708,0,1)
    )
  }

}
