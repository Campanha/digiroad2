package fi.liikennevirasto.digiroad2.user.oracle

import org.scalatest.{Matchers, FunSuite}
import fi.liikennevirasto.digiroad2.util.SqlScriptRunner._
import fi.liikennevirasto.digiroad2.user.Configuration

class OracleUserProviderSpec extends FunSuite with Matchers {
  val TestUserName = "Oracleuserprovidertest"
  val north = 1000

  val provider = new OracleUserProvider

  test("create and get user") {
    executeStatement("DELETE FROM service_user WHERE username = '" + TestUserName.toLowerCase() + "'");
    provider.getUser(TestUserName) shouldBe (None)
    provider.createUser(TestUserName, Configuration(north = Some(1000)))
    val user = provider.getUser(TestUserName).get
    user.username should be (TestUserName.toLowerCase)
    user.configuration.north should be (Some(north))
  }
}