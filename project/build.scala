import io.gatling.sbt.GatlingPlugin
import sbt._
import sbt.Keys._
import org.scalatra.sbt._
import sbtassembly.Plugin.AssemblyKeys._
import sbtassembly.Plugin.MergeStrategy
import org.scalatra.sbt.PluginKeys._

object Digiroad2Build extends Build {
  val Organization = "fi.liikennevirasto"
  val Digiroad2Name = "digiroad2"
  val Digiroad2GeoName = "digiroad2-geo"
  val Version = "0.1.0-SNAPSHOT"
  val ScalaVersion = "2.11.7"
  val ScalatraVersion = "2.6.3"
  val env = if (System.getProperty("digiroad2.env") != null) System.getProperty("digiroad2.env") else "dev"
  val testEnv = if (System.getProperty("digiroad2.env") != null) System.getProperty("digiroad2.env") else "test"
  lazy val geoJar = Project (
    Digiroad2GeoName,
    file(Digiroad2GeoName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2GeoName,
      version := Version,
      scalaVersion := ScalaVersion,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      resolvers := Seq(
        Classpaths.typesafeReleases,
        "osgeo" at "http://download.osgeo.org/webdav/geotools/",
        "opengeo" at "http://repo.boundlessgeo.com/main/"),
      libraryDependencies ++= Seq(
        "org.joda" % "joda-convert" % "2.0.1",
        "joda-time" % "joda-time" % "2.9.9",
        "com.typesafe.akka" %% "akka-actor" % "2.5.12",
        "javax.media" % "jai_core" % "1.1.3" from "http://download.osgeo.org/webdav/geotools/javax/media/jai_core/1.1.3/jai_core-1.1.3.jar",
        "org.geotools" % "gt-graph" % "19.0",
        "org.scalatest" % "scalatest_2.11" % "3.2.0-SNAP7" % "test"
      )
    )
  )

  val Digiroad2OracleName = "digiroad2-oracle"
  lazy val oracleJar = Project (
    Digiroad2OracleName,
    file(Digiroad2OracleName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2OracleName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers ++= Seq(Classpaths.typesafeReleases,
        "maven-public" at "http://livibuild04.vally.local/nexus/repository/maven-public/",
        "ivy-public" at "http://livibuild04.vally.local/nexus/repository/ivy-public/"),
      scalacOptions ++= Seq("-unchecked", "-feature"),
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.apache.commons" % "commons-lang3" % "3.2",
        "commons-codec" % "commons-codec" % "1.9",
        "com.jolbox" % "bonecp" % "0.8.0.RELEASE",
        "org.scalatest" % "scalatest_2.11" % "3.2.0-SNAP7" % "test",
        "com.typesafe.slick" %% "slick" % "3.0.0",
        "org.json4s"   %% "json4s-jackson" % "3.5.3",
        "org.scala-lang.modules"   %% "scala-parser-combinators" % "1.1.0",
        "org.joda" % "joda-convert" % "2.0.1",
        "joda-time" % "joda-time" % "2.9.9",
        "com.github.tototoshi" %% "slick-joda-mapper" % "2.2.0",
        "com.github.tototoshi" %% "scala-csv" % "1.3.5",
        "org.apache.httpcomponents" % "httpclient" % "4.5.5",
        "com.newrelic.agent.java" % "newrelic-api" % "3.1.1",
        "org.mockito" % "mockito-core" % "2.18.3" % "test",
        "com.googlecode.flyway" % "flyway-core" % "2.3.1" % "test",
        "com.oracle" % "ojdbc6" % "11.2.0.3.0",
        "com.oracle" % "sdoapi" % "11.2.0",
        "com.oracle" % "sdoutl" % "11.2.0",
        "javax.mail" % "javax.mail-api" % "1.6.1",
        "com.sun.mail" % "javax.mail" % "1.6.1"
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv,
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf" /  env
    )
  ) dependsOn(geoJar)

  val Digiroad2ApiName = "digiroad2-api-common"
  lazy val commonApiJar = Project (
    Digiroad2ApiName,
    file(Digiroad2ApiName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2ApiName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      //      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.5.12",
        "org.apache.httpcomponents" % "httpclient" % "4.5.5",
        "org.scalatest" % "scalatest_2.11" % "3.2.0-SNAP7" % "compile,test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.scalatra" %% "scalatra-swagger"  % "2.6.3",
        "org.mockito" % "mockito-core" % "2.18.3" % "test",
        "org.joda" % "joda-convert" % "2.0.1",
        "joda-time" % "joda-time" % "2.9.9",
        "org.eclipse.jetty" % "jetty-webapp" % "9.2.15.v20160210" % "compile",
        "org.eclipse.jetty" % "jetty-servlets" % "9.2.15.v20160210" % "compile",
        "org.eclipse.jetty" % "jetty-proxy" % "9.2.15.v20160210" % "compile",
        "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "provided;test" artifacts (Artifact("javax.servlet", "jar", "jar"))
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv,
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf" /  env
    )
  ) dependsOn(geoJar, oracleJar)

  val Digiroad2OTHApiName = "digiroad2-api-oth"
  lazy val othApiJar = Project (
    Digiroad2OTHApiName,
    file(Digiroad2OTHApiName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2OTHApiName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      //      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.json4s"   %% "json4s-jackson" % "3.5.3",
        "org.json4s"   %% "json4s-native" % "3.5.2",
        "org.scala-lang.modules"   %% "scala-parser-combinators" % "1.1.0",
        "org.scalatest" % "scalatest_2.11" % "3.2.0-SNAP7" % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.mockito" % "mockito-core" % "2.18.3" % "test",
        "com.typesafe.akka" %% "akka-testkit" % "2.5.12" % "test",
        "ch.qos.logback" % "logback-classic" % "1.2.3" % "runtime",
        "commons-io" % "commons-io" % "2.6",
        "com.newrelic.agent.java" % "newrelic-api" % "3.1.1",
        "org.apache.httpcomponents" % "httpclient" % "4.3.3",
        "org.scalatra" %% "scalatra-swagger"  % "2.6.3"
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv,
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf" /  env
    )
  ) dependsOn(geoJar, oracleJar, commonApiJar % "compile->compile;test->test")

  lazy val warProject = Project (
    Digiroad2Name,
    file("."),
    settings = Defaults.defaultSettings
      ++ assemblySettings
      ++ net.virtualvoid.sbt.graph.Plugin.graphSettings
      ++ ScalatraPlugin.scalatraWithJRebel ++ Seq(
      organization := Organization,
      name := Digiroad2Name,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      parallelExecution in Test := false,
      fork in (Compile,run) := true,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.json4s"   %% "json4s-jackson" % "3.5.3",
        "org.scala-lang.modules"   %% "scala-parser-combinators" % "1.1.0",
        "org.scalatest" % "scalatest_2.11" % "3.2.0-SNAP7" % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.scalatra" %% "scalatra-swagger"  % "2.6.3",
        "org.mockito" % "mockito-core" % "2.18.3" % "test",
        "com.typesafe.akka" %% "akka-testkit" % "2.5.12" % "test",
        "ch.qos.logback" % "logback-classic" % "1.2.3" % "runtime",
        "commons-io" % "commons-io" % "2.6",
        "com.newrelic.agent.java" % "newrelic-api" % "3.1.1",
        "org.apache.httpcomponents" % "httpclient" % "4.3.3",
        "org.eclipse.jetty" % "jetty-webapp" % "9.2.15.v20160210" % "container;compile",
        "org.eclipse.jetty" % "jetty-servlets" % "9.2.15.v20160210" % "container;compile",
        "org.eclipse.jetty" % "jetty-proxy" % "9.2.15.v20160210" % "container;compile",
        "org.eclipse.jetty" % "jetty-jmx" % "9.2.15.v20160210" % "container;compile",
        "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container;provided;test" artifacts (Artifact("javax.servlet", "jar", "jar"))
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv
    )
  ) dependsOn(geoJar, oracleJar, commonApiJar, othApiJar) aggregate
    (geoJar, oracleJar, commonApiJar, othApiJar)

  lazy val gatling = project.in(file("digiroad2-gatling"))
    .enablePlugins(GatlingPlugin)
    .settings(scalaVersion := ScalaVersion)
    .settings(libraryDependencies ++= Seq(
    "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.1.7" % "test",
    "io.gatling" % "gatling-test-framework" % "2.1.7" % "test"))

  val assemblySettings = sbtassembly.Plugin.assemblySettings ++ Seq(
    mainClass in assembly := Some("fi.liikennevirasto.digiroad2.ProductionServer"),
    test in assembly := {},
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
    {
      case x if x.endsWith("about.html") => MergeStrategy.discard
      case x => old(x)
    } }
  )
}
