import sbt.*

object Dependencies {

  object V {
    val catsEffect = "3.5.4"
    val ciris      = "3.5.0"
    val fs2        = "3.10.2"
    val http4s     = "0.23.27"
    val iron       = "2.5.0"
  }

  def http4s(artifact: String) = "org.http4s" %% s"http4s-$artifact" % V.http4s

  val catsCore   = "org.typelevel" %% "cats-core"   % "2.10.0"
  val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect

  val fs2         = "co.fs2"  %% "fs2-core"           % V.fs2
  val fs2IO       = "co.fs2"  %% "fs2-io"             % V.fs2

  val cirisCore  = "is.cir"             %% "ciris"        % V.ciris
  val cirisHtt4s = "is.cir"             %% "ciris-http4s" % V.ciris
  val iron       = "io.github.iltotore" %% "iron"         % V.iron
  val ironCiris  = "io.github.iltotore" %% "iron-ciris"   % V.iron

  val http4sServer      = http4s("ember-server")
  val http4sClient      = http4s("client")
  val http4sEmberClient = http4s("ember-client")

  val log4Cats = "org.typelevel" %% "log4cats-slf4j"  % "2.7.0"
  val logbackX  = "ch.qos.logback" % "logback-classic" % "1.5.6"

  val ducktape = "io.github.arainko" %% "ducktape" % "0.2.0"


  val testContainers    = "com.dimafeng"        %% "testcontainers-scala-postgresql" % "0.41.3"     % Test
  val weaver            = "com.disneystreaming" %% "weaver-cats"                     % "0.8.4"      % Test
  val weaverScalaCheck  = "com.disneystreaming" %% "weaver-scalacheck"               % "0.8.4"      % Test
  val catsEffectTestKit = "org.typelevel"       %% "cats-effect-testkit"             % V.catsEffect % Test
  val scalacheck        = "org.scalacheck"      %% "scalacheck"                      % "1.17.0"     % Test
}