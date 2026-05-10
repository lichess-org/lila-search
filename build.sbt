import Dependencies.*
import org.typelevel.scalacoptions.ScalacOptions

scalaVersion := "3.8.3"
versionScheme := Some("early-semver")
organization := "org.lichess.search"
run / fork := true
run / javaOptions += "-Dconfig.override_with_env_vars=true"
semanticdbEnabled := true // for scalafix
resolvers ++= ourResolvers

tpolecatScalacOptions ++= Set(
  ScalacOptions.other("-rewrite"),
  ScalacOptions.other("-indent"),
  ScalacOptions.explain,
  ScalacOptions.release("21"),
  ScalacOptions.other("-Wall")
)
Compile / doc / sources := Seq.empty

val dockerSettings = Seq(
  dockerBaseImage := "eclipse-temurin:25-jdk-noble",
  dockerUpdateLatest := true,
  dockerBuildxPlatforms := Seq("linux/amd64", "linux/arm64"),
  Docker / maintainer := "lichess.org",
  Docker / dockerRepository := Some("ghcr.io")
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
    smithy4sWildcardArgument := "?",
    libraryDependencies ++= Seq(
      catsCore,
      smithy4sCore
    )
  )
  .dependsOn(core)

lazy val elastic = project
  .in(file("modules/elastic"))
  .enablePlugins(Smithy4sCodegenPlugin, Snapshot4sPlugin)
  .settings(
    name := "elastic",
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      catsMtl,
      http4sClient,
      elastic4sHttp4sClient,
      smithy4sCore,
      weaver,
      snapshot4s
    ),
    Test / scalacOptions += "-Wconf:msg=interpolation uses toString:s"
  )
  .dependsOn(core)

lazy val lilaMongo = project
  .in(file("modules/lila-mongo"))
  .settings(
    name := "lila-mongo",
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

lazy val lilaGameExport = project
  .in(file("modules/lila-game-export"))
  .settings(
    name := "lila-game-export",
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
      declineCatsEffect,
      weaver
    )
  )
  .dependsOn(lilaMongo)

lazy val ingestorApp = project
  .in(file("modules/ingestor-app"))
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin, DockerPlugin)
  .settings(
    name := "lila-search-ingestor",
    buildInfoSettings,
    Docker / packageName := "lichess-org/lila-search-ingestor-app",
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      logback % Runtime,
      otel4sSdk,
      otel4sSdkMetrics,
      otel4sPrometheusExporter,
      otel4sInstrumentationMetrics
    ),
    dockerSettings,
    Compile / doc / sources := Seq.empty,
  )
  .dependsOn(ingestorCore)

lazy val ingestorCli = project
  .in(file("modules/ingestor-cli"))
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin, DockerPlugin)
  .settings(
    name := "lila-search-cli",
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
    dockerSettings,
    Compile / doc / sources := Seq.empty,
  )
  .dependsOn(elastic, core, ingestorCore)

lazy val ingestorCore = project
  .in(file("modules/ingestor-core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "ingestor-core",
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
    Compile / doc / sources := Seq.empty,
    Test / scalacOptions += "-Wconf:msg=interpolation uses toString:s"
  )
  .dependsOn(elastic, core, lilaMongo)

lazy val client = project
  .in(file("modules/client"))
  .settings(
    name := "client",
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
      otel4sSdkMetrics,
      otel4sPrometheusExporter,
      otel4sInstrumentationMetrics,
      otel4sHttp4sCore,
      otel4sHttp4sMetrics
    ),
    dockerSettings,
    Compile / doc / sources := Seq.empty,
  )
  .dependsOn(api, elastic)

val e2e = project
  .in(file("modules/e2e"))
  .settings(
    publish := {},
    publish / skip := true,
    libraryDependencies ++= Seq(testContainers, weaver)
  )
  .dependsOn(client, app, ingestorCore)

lazy val root = rootProject.autoAggregate

Global / excludeLintKeys ++= Set(
  git.gitDescribedVersion,
  git.gitUncommittedChanges,
  com.typesafe.sbt.packager.Keys.executableScriptName,
  com.typesafe.sbt.packager.Keys.daemonStdoutLogFile,
  com.typesafe.sbt.packager.Keys.rpmScriptsDirectory,
  Keys.sourceDirectory,
  Keys.name
)

addCommandAlias("prepare", "scalafixAll; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check ; scalafmtCheckAll")
