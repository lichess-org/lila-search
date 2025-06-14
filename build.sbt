import Dependencies.*
import org.typelevel.scalacoptions.ScalacOptions

inThisBuild(
  Seq(
    scalaVersion  := "3.7.1",
    versionScheme := Some("early-semver"),
    organization  := "org.lichess.search",
    run / fork    := true,
    run / javaOptions += "-Dconfig.override_with_env_vars=true",
    semanticdbEnabled := true, // for scalafix
    resolvers ++= ourResolvers,
    Compile / doc / sources := Seq.empty,
    publishTo               := Option(Resolver.file("file", new File(sys.props.getOrElse("publishTo", ""))))
  )
)

val commonSettings = Seq(
  tpolecatScalacOptions ++= Set(
    ScalacOptions.other("-rewrite"),
    ScalacOptions.other("-indent"),
    ScalacOptions.explain,
    ScalacOptions.release("21"),
    ScalacOptions.other("-Wall")
  ),
  resolvers += "jitpack".at("https://jitpack.io")
)

val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    version,
    BuildInfoKey.map(git.gitHeadCommit) { case (k, v) => k -> v.getOrElse("unknown").take(7) }
  ),
  buildInfoPackage := "lila.search"
)

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "core",
    commonSettings,
    libraryDependencies ++= Seq(
      catsCore,
      smithy4sCore
    )
  )

lazy val api = project
  .in(file("modules/api"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "api",
    commonSettings,
    smithy4sWildcardArgument := "?",
    libraryDependencies ++= Seq(
      catsCore,
      smithy4sCore
    )
  )
  .dependsOn(core)

lazy val elastic = project
  .in(file("modules/elastic"))
  .settings(
    name := "elastic",
    commonSettings,
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      http4sClient,
      elastic4sHttp4sClient,
      otel4sCore
    )
  )
  .dependsOn(api, core)

lazy val ingestor = project
  .in(file("modules/ingestor"))
  .enablePlugins(JavaAppPackaging, Smithy4sCodegenPlugin, BuildInfoPlugin)
  .settings(
    name := "ingestor",
    commonSettings,
    buildInfoSettings,
    dockerBaseImage := "docker.io/eclipse-temurin:21-jdk",
    publish         := {},
    publish / skip  := true,
    libraryDependencies ++= Seq(
      chess,
      catsCore,
      fs2,
      fs2IO,
      catsEffect,
      declineCore,
      declineCatsEffect,
      ducktape,
      cirisCore,
      cirisHtt4s,
      smithy4sCore,
      smithy4sJson,
      jsoniterCore,
      jsoniterMacro,
      circe,
      http4sServer,
      http4sEmberClient,
      mongo4catsCore,
      mongo4catsCirce,
      log4Cats,
      logback % Runtime,
      otel4sSdk,
      otel4sMetrics,
      otel4sPrometheusExporter,
      otel4sInstrumentationMetrics,
      weaver,
      weaverScalaCheck
    ),
    Compile / doc / sources := Seq.empty,
    Compile / run / fork    := true
  )
  .dependsOn(elastic, core)

lazy val client = project
  .in(file("modules/client"))
  .settings(
    name := "client",
    commonSettings,
    libraryDependencies ++= Seq(
      smithy4sJson,
      jsoniterCore,
      jsoniterMacro,
      playWS
    )
  )
  .dependsOn(api, core)

lazy val app = project
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin)
  .in(file("modules/app"))
  .settings(
    name := "lila-search",
    commonSettings,
    buildInfoSettings,
    dockerBaseImage := "docker.io/eclipse-temurin:21-jdk",
    publish         := {},
    publish / skip  := true,
    libraryDependencies ++= Seq(
      smithy4sHttp4s,
      jsoniterCore,
      jsoniterMacro,
      smithy4sHttp4sSwagger,
      catsCore,
      catsEffect,
      ducktape,
      http4sServer,
      http4sEmberClient,
      cirisCore,
      cirisHtt4s,
      log4Cats,
      logback % Runtime,
      otel4sSdk,
      otel4sMetrics,
      otel4sPrometheusExporter,
      otel4sInstrumentationMetrics
    ),
    Compile / doc / sources := Seq.empty,
    Compile / run / fork    := true
  )
  .dependsOn(api, elastic)

val e2e = project
  .in(file("modules/e2e"))
  .settings(
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(testContainers, weaver)
  )
  .dependsOn(client, app, ingestor)

lazy val root = project
  .in(file("."))
  .settings(publish := {}, publish / skip := true)
  .aggregate(core, api, app, client, e2e, elastic, ingestor)

addCommandAlias("prepare", "scalafixAll; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check ; scalafmtCheckAll")
