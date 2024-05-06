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

lazy val app = project
  .in(file("app"))
  .enablePlugins(PlayScala)
  .disablePlugins(PlayFilters)
  .settings(
    name         := "lila-search",
    organization := "org.lichess",
    name := "lila-search",
    libraryDependencies ++= Seq(
      "com.github.ornicar"     %% "scalalib"                % "7.1.0",
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % "7.17.4",
      "com.typesafe.play"      %% "play-json"               % "2.9.4",
      "com.typesafe.play"      %% "play-json-joda"          % "2.9.4"
    ),
    scalacOptions ++= Seq(
      "-explaintypes",
      "-feature",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:postfixOps",
      "-Ymacro-annotations",
      // Warnings as errors!
      // "-Xfatal-warnings",
      // Linting options
      "-unchecked",
      "-Xcheckinit",
      "-Xlint:adapted-args",
      "-Xlint:constant",
      "-Xlint:delayedinit-select",
      "-Xlint:deprecation",
      "-Xlint:inaccessible",
      "-Xlint:infer-any",
      "-Xlint:missing-interpolator",
      "-Xlint:nullary-unit",
      "-Xlint:option-implicit",
      "-Xlint:package-object-classes",
      "-Xlint:poly-implicit-overload",
      "-Xlint:private-shadow",
      "-Xlint:stars-align",
      "-Xlint:type-parameter-shadow",
      "-Wdead-code",
      "-Wextra-implicit",
      "-Wnumeric-widen",
      "-Wunused:imports",
      "-Wunused:locals",
      "-Wunused:patvars",
      "-Wunused:privates",
      "-Wunused:implicits",
      "-Wunused:params"
      /* "-Wvalue-discard" */
    ),

    // Play provides two styles of routers, one expects its actions to be injected, the
    // other, legacy style, accesses its actions statically.
    routesGenerator := InjectedRoutesGenerator
  )


lazy val root = project
  .in(file("."))
  .aggregate(app)

