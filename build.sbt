import org.typelevel.scalacoptions.ScalacOptions

inThisBuild(
  Seq(
    scalaVersion  := "2.13.14",
    versionScheme := Some("early-semver"),
    version       := "3.0.0-SNAPSHOT",
    run / fork    := true,
    run / javaOptions += "-Dconfig.override_with_env_vars=true",
    Compile / doc / sources                := Seq.empty,
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
    name := "core",
    libraryDependencies ++= Seq(
      "com.github.ornicar" %% "scalalib"                % "7.1.0",
      "nl.gn0s1s"          %% "elastic4s-client-esjava" % "8.12.0",
      "joda-time"           % "joda-time"               % "2.12.7"
    )
  )

lazy val play = project
  .in(file("play"))
  .enablePlugins(PlayScala)
  .disablePlugins(PlayFilters)
  .settings(
    commonSettings,
    tpolecatExcludeOptions += ScalacOptions.fatalWarnings,
    name := "lila-search",
    libraryDependencies ++= Seq(
      "com.github.ornicar" %% "scalalib"       % "7.1.0",
      "com.typesafe.play"  %% "play-json"      % "2.9.4",
      "com.typesafe.play"  %% "play-json-joda" % "2.9.4"
    ),
    // Play provides two styles of routers, one expects its actions to be injected, the
    // other, legacy style, accesses its actions statically.
    routesGenerator := InjectedRoutesGenerator
  )
  .dependsOn(core)

lazy val root = project
  .in(file("."))
  .aggregate(core, play)
