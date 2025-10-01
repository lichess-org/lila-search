import sbt.*
import smithy4s.codegen.BuildInfo.version as smithy4sVersion

object Dependencies {

  val lilaMaven = "lila-maven" at "https://raw.githubusercontent.com/lichess-org/lila-maven/master"
  val jitpack = "jitpack".at("https://jitpack.io")
  val ourResolvers = Seq(lilaMaven, jitpack, Resolver.sonatypeCentralSnapshots)

  object V {
    val catsEffect = "3.6.3"
    val catsMtl    = "1.6.0"
    val ciris      = "3.11.0"
    val chess      = "17.12.3"
    val decline    = "2.5.0"
    val elastic4s  = "9.1.0"
    val fs2        = "3.12.2"
    val http4s     = "0.23.30"
    val mongo4cats = "0.7.13"
    val otel4s     = "0.13.2"
  }

  def http4s(artifact: String)   = "org.http4s"                   %% s"http4s-$artifact"   % V.http4s
  def smithy4s(artifact: String) = "com.disneystreaming.smithy4s" %% s"smithy4s-$artifact" % smithy4sVersion

  val chess = "com.github.lichess-org.scalachess" %% "scalachess" % V.chess

  val catsCore   = "org.typelevel" %% "cats-core"   % "2.13.0"
  val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
  val catsMtl    = "org.typelevel" %% "cats-mtl" % V.catsMtl

  val fs2   = "co.fs2" %% "fs2-core" % V.fs2
  val fs2IO = "co.fs2" %% "fs2-io"   % V.fs2

  val cirisCore  = "is.cir"             %% "ciris"        % V.ciris
  val cirisHtt4s = "is.cir"             %% "ciris-http4s" % V.ciris

  val http4sServer      = http4s("ember-server")
  val http4sClient      = http4s("client")
  val http4sEmberClient = http4s("ember-client")

  lazy val smithy4sCore          = smithy4s("core")
  lazy val smithy4sHttp4s        = smithy4s("http4s")
  lazy val smithy4sHttp4sSwagger = smithy4s("http4s-swagger")
  lazy val smithy4sJson          = smithy4s("json")

  val jsoniterCore = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.37.10"
  val jsoniterMacro = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.37.10"

  val playWS = "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.2.12"

  val elastic4sHttp4sClient = "nl.gn0s1s" %% "elastic4s-client-http4s" % V.elastic4s

  val mongo4catsCore = "io.github.kirill5k" %% "mongo4cats-core" % V.mongo4cats
  val mongo4catsCirce = "io.github.kirill5k" %% "mongo4cats-circe" % V.mongo4cats
  val circe = "io.circe" %% "circe-core" % "0.14.15"

  val otel4sCore =  "org.typelevel" %% "otel4s-core" % V.otel4s
  val otel4sPrometheusExporter = "org.typelevel" %% "otel4s-sdk-exporter-prometheus" % V.otel4s
  val otel4sSdk = "org.typelevel" %% "otel4s-sdk" % V.otel4s
  val otel4sInstrumentationMetrics =   "org.typelevel" %% "otel4s-instrumentation-metrics" % V.otel4s
  val otel4sMetrics = "org.typelevel" %% "otel4s-experimental-metrics" % "0.7.0"

  val log4Cats = "org.typelevel" %% "log4cats-slf4j"  % "2.7.1"
  val logback = "ch.qos.logback" % "logback-classic" % "1.5.19"

  val ducktape = "io.github.arainko" %% "ducktape" % "0.2.10"

  val declineCore = "com.monovore" %% "decline" % V.decline
  val declineCatsEffect = "com.monovore" %% "decline-effect" % V.decline

  val testContainers    = "com.dimafeng"        %% "testcontainers-scala-core"       % "0.43.0"     % Test
  val weaver            = "org.typelevel" %% "weaver-cats"                     % "0.10.1"      % Test
  val weaverScalaCheck  = "org.typelevel" %% "weaver-scalacheck"               % "0.10.1"      % Test
  val catsEffectTestKit = "org.typelevel"       %% "cats-effect-testkit"             % V.catsEffect % Test
  val scalacheck        = "org.scalacheck"      %% "scalacheck"                      % "1.17.0"     % Test
}
