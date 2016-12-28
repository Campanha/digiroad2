package fi.liikennevirasto.digiroad2.util

import java.util.Properties
import org.apache.commons.codec.binary.Base64

class TierekisteriAuthPropertyReader {

  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/keys.properties"))
    props
  }

  private def getUsername: String = {
    val loadedKeyString = properties.getProperty("tierekisteri.username")
    println("u = "+loadedKeyString)
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing TR username")
    loadedKeyString
  }

  private def getPassword: String = {
    val loadedKeyString = properties.getProperty("tierekisteri.password")
    println("p = "+loadedKeyString)
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing TR Password")
    loadedKeyString
  }

  def getAuthInBase64: String = {
    Base64.encodeBase64String((getUsername + ":" + getPassword).getBytes)
  }
}
