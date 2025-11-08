import Dependencies.*
import org.typelevel.scalacoptions.ScalacOptions

inThisBuild(
  Seq(
    scalaVersion := "3.7.3",
    versionScheme := Some("early-semver"),
    organization := "org.lichess.search",
    run / fork := true,
    run / javaOptions += "-Dconfig.override_with_env_vars=true",
    semanticdbEnabled := true, // for scalafix
    resolvers ++= ourResolvers,
    Compile / doc / sources := Seq.empty,
    publishTo := Option(Resolver.file("file", new File(sys.props.getOrElse("publishTo", "")))),
    dockerBaseImage := "eclipse-temurin:25-jdk-noble",
    dockerUpdateLatest := true,
    dockerBuildxPlatforms := Seq("linux/amd64", "linux/arm64"),
    Docker / maintainer := "lichess.org",
    Docker / dockerRepository := Some("ghcr.io")
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
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      catsMtl,
      http4sClient,
      elastic4sHttp4sClient
    )
  )
  .dependsOn(core)

lazy val `lila-mongo` = project
  .in(file("modules/lila-mongo"))
  .settings(
    name := "lila-mongo",
    commonSettings,
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      fs2,
      fs2IO,
      mongo4catsCore,
      mongo4catsCirce,
      chess,
      log4Cats
    )
  )
  .dependsOn(core)

lazy val `lila-game-export` = project
  .in(file("modules/lila-game-export"))
  .settings(
    name := "lila-game-export",
    commonSettings,
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      fs2,
      fs2IO,
      fs2DataCsv,
      fs2DataCsvGeneric,
      chess,
      log4Cats,
      declineCore,
      declineCatsEffect
    )
  )
  .dependsOn(`lila-mongo`)

lazy val `ingestor-app` = project
  .in(file("modules/ingestor-app"))
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin, DockerPlugin)
  .settings(
    name := "lila-search-ingestor",
    commonSettings,
    buildInfoSettings,
    Docker / packageName := "lichess-org/lila-search-ingestor-app",
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      logback % Runtime,
      otel4sSdk,
      otel4sMetrics,
      otel4sPrometheusExporter,
      otel4sInstrumentationMetrics
    ),
    Compile / doc / sources := Seq.empty,
    Compile / run / fork := true
  )
  .dependsOn(`ingestor-core`)

lazy val `ingestor-cli` = project
  .in(file("modules/ingestor-cli"))
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin, DockerPlugin)
  .settings(
    name := "lila-search-cli",
    commonSettings,
    buildInfoSettings,
    Docker / packageName := "lichess-org/lila-search-ingestor-cli",
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      declineCore,
      declineCatsEffect,
      otel4sCore,
      logback % Runtime,
      weaver
    ),
    Compile / doc / sources := Seq.empty,
    Compile / run / fork := true
  )
  .dependsOn(elastic, core, `ingestor-core`, `lila-game-export`)

lazy val `ingestor-core` = project
  .in(file("modules/ingestor-core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "ingestor-core",
    commonSettings,
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      catsCore,
      fs2,
      fs2IO,
      catsEffect,
      cirisCore,
      cirisHtt4s,
      ducktape,
      smithy4sCore,
      smithy4sJson,
      jsoniterCore,
      jsoniterMacro,
      circe,
      otel4sCore,
      otel4sHttp4sCore,
      otel4sHttp4sMetrics,
      http4sEmberClient,
      log4Cats,
      weaver,
      weaverScalaCheck
    ),
    Compile / doc / sources := Seq.empty
  )
  .dependsOn(elastic, core, `lila-mongo`)

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
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin, DockerPlugin)
  .in(file("modules/app"))
  .settings(
    name := "lila-search-app",
    commonSettings,
    buildInfoSettings,
    Docker / packageName := "lichess-org/lila-search-app",
    publish := {},
    publish / skip := true,
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
      otel4sInstrumentationMetrics,
      otel4sHttp4sCore,
      otel4sHttp4sMetrics
    ),
    Compile / doc / sources := Seq.empty,
    Compile / run / fork := true
  )
  .dependsOn(api, elastic)

val e2e = project
  .in(file("modules/e2e"))
  .settings(
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(testContainers, weaver)
  )
  .dependsOn(client, app, `ingestor-core`)

lazy val root = project
  .in(file("."))
  .settings(publish := {}, publish / skip := true)
  .aggregate(
    core,
    api,
    app,
    client,
    e2e,
    elastic,
    `lila-mongo`,
    `lila-game-export`,
    `ingestor-core`,
    `ingestor-app`,
    `ingestor-cli`
  )

addCommandAlias("prepare", "scalafixAll; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check ; scalafmtCheckAll")
