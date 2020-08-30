name := """lila-search"""

version := "2.0"

scalaVersion := "2.13.2"

lazy val root = project.in(file("."))
  .enablePlugins(PlayScala)
  .disablePlugins(PlayFilters)

sources in doc in Compile := List()
// disable publishing the main API jar
publishArtifact in (Compile, packageDoc) := false
// disable publishing the main sources jar
publishArtifact in (Compile, packageSrc) := false

scalacOptions ++= Seq(
  "-language:implicitConversions",
  "-language:postfixOps",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Xlint:_",
  "-Ywarn-macros:after",
  "-Ywarn-unused:_",
  /* "-Xfatal-warnings", */
  "-Xmaxerrs", "12",
  "-Xmaxwarns", "12",
  s"-Wconf:src=${target.value}/.*:s"
)

val elastic4sVersion = "7.9.0"

libraryDependencies ++= Seq(
  "com.github.ornicar" %% "scalalib" % "6.8",
  "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,
  "com.typesafe.play" %% "play-json" % "2.9.0",
  "com.typesafe.play" %% "play-json-joda" % "2.9.0",
  ws,
  specs2 % Test
)

resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator
