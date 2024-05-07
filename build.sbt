import org.typelevel.scalacoptions.ScalacOption
import org.typelevel.scalacoptions.ScalacOptions
import Dependencies.*

lazy val scala213 = "2.13.14"
lazy val scala3 = "3.4.1"
lazy val supportedScalaVersions = List(scala213, scala3)

inThisBuild(
  Seq(
    scalaVersion := scala213,
    versionScheme := Some("early-semver"),
    version := "3.0.0-SNAPSHOT",
    run / fork := true,
    run / javaOptions += "-Dconfig.override_with_env_vars=true",
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"
  )
)

val commonSettings = Seq(
)

lazy val core = project
  .in(file("modules/core"))
  .settings(
    commonSettings,
    crossScalaVersions := supportedScalaVersions,
    tpolecatScalacOptions ++= Set(ScalacOptions.source3),
    name := "lila-search-core",
    libraryDependencies ++= Seq(
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % "8.11.5",
      "joda-time" % "joda-time" % "2.12.7"
    )
  )

lazy val play = project
  .in(file("play"))
  .enablePlugins(PlayScala)
  .disablePlugins(PlayFilters)
  .settings(
    commonSettings,
    tpolecatExcludeOptions += ScalacOptions.fatalWarnings,
    name := "lila-search-play",
    libraryDependencies ++= Seq(
      "com.github.ornicar" %% "scalalib" % "7.1.0",
      "com.typesafe.play" %% "play-json" % "2.9.4",
      "com.typesafe.play" %% "play-json-joda" % "2.9.4"
    ),
    // Play provides two styles of routers, one expects its actions to be injected, the
    // other, legacy style, accesses its actions statically.
    routesGenerator := InjectedRoutesGenerator
  )
  .dependsOn(core)

lazy val api = (project in file("modules/api"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "lila-search-api",
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion.value
    )
  )

lazy val app = (project in file("modules/app"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "lila-search",
    commonSettings,
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s"         % smithy4sVersion.value,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s-swagger" % smithy4sVersion.value,
      http4sServer,
      http4sEmberClient,
      cirisCore,
      cirisHtt4s,
      logbackX
    ),
    Compile / run / fork         := true,
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(api, core)

lazy val root = project
  .in(file("."))
  .settings(publish := {}, publish / skip := true)
  .aggregate(core, play, api, app)
