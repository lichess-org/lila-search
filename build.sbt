import org.typelevel.scalacoptions.{ ScalacOption, ScalacOptions }
import Dependencies.*

lazy val scala213               = "2.13.14"
lazy val scala3                 = "3.4.2"
lazy val supportedScalaVersions = List(scala213, scala3)

inThisBuild(
  Seq(
    scalaVersion  := scala213,
    versionScheme := Some("early-semver"),
    version       := "3.0.0-RC5",
    organization  := "org.lichess.search",
    run / fork    := true,
    run / javaOptions += "-Dconfig.override_with_env_vars=true",
    Compile / doc / sources                := Seq.empty,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    publishTo := Option(Resolver.file("file", new File(sys.props.getOrElse("publishTo", ""))))
  )
)

val commonSettings = Seq(
  scalaVersion := scala3,
  excludeDependencies ++= Seq(
    "org.typelevel"                % "cats-core_2.13",
    "org.typelevel"                % "cats-kernel_2.13",
    "nl.gn0s1s"                    % "elastic4s-core_2.13",
    "nl.gn0s1s"                    % "elastic4s-domain_2.13",
    "nl.gn0s1s"                    % "elastic4s-http_2.13",
    "com.fasterxml.jackson.module" % "jackson-module-scala_2.13",
    "org.scala-lang.modules"       % "scala-collection-compat_2.13",
    "com.disneystreaming.smithy4s" % "smithy4s-core_2.13"
  )
)

lazy val core = project
  .in(file("modules/core"))
  .settings(
    crossScalaVersions := supportedScalaVersions,
    tpolecatScalacOptions ++= Set(ScalacOptions.source3),
    name           := "lila-search-core",
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      elastic4sJavaClient,
      catsCore,
      "joda-time" % "joda-time" % "2.12.7"
    )
  )

lazy val play = project
  .in(file("play"))
  .enablePlugins(PlayScala)
  .disablePlugins(PlayFilters)
  .settings(
    tpolecatExcludeOptions += ScalacOptions.fatalWarnings,
    name           := "lila-search",
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-json"      % "2.9.4",
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
    name := "api",
    commonSettings,
    smithy4sWildcardArgument := "?",
    libraryDependencies ++= Seq(
      catsCore,
      smithy4sCore
    )
  )

lazy val client = (project in file("modules/client"))
  .settings(
    name := "client",
    commonSettings,
    libraryDependencies ++= Seq(
      smithy4sJson,
      playWS
    )
  )
  .dependsOn(api)

lazy val app = (project in file("modules/app"))
  .settings(
    name           := "lila-search-v3",
    publish        := {},
    publish / skip := true,
    commonSettings,
    libraryDependencies ++= Seq(
      smithy4sHttp4s,
      smithy4sHttp4sSwagger,
      elastic4sCatsEffect,
      catsCore,
      catsEffect,
      ducktape,
      http4sServer,
      http4sEmberClient,
      cirisCore,
      cirisHtt4s,
      log4Cats,
      logbackX
    ),
    Compile / run / fork := true
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(api, core)

val e2e = (project in file("modules/e2e"))
  .settings(
    commonSettings,
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(weaver)
  )
  .dependsOn(client, app)

lazy val root = project
  .in(file("."))
  .settings(publish := {}, publish / skip := true)
  .aggregate(core, play, api, app, client, e2e)
