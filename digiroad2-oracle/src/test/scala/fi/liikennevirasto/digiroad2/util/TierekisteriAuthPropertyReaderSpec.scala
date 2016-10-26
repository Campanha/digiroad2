package fi.liikennevirasto.digiroad2.util

import org.scalatest.{FunSuite, Matchers}

class TierekisteriAuthPropertyReaderSpec extends FunSuite with Matchers {
  val reader = new TierekisteriAuthPropertyReader

  test("Basic64 authentication for TR client") {
    val authenticate = reader.getAuthInBase64
    authenticate should be ("dXNlclhZWjpwYXNzd29yZFhZWg==")
  }
}
