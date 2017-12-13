import com.typesafe.sbt.SbtScalariform.autoImport.scalariformFormat
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import scalariform.formatter.preferences._

name := """lila-search"""

version := "1.4"

scalaVersion := "2.12.4"

lazy val root = project.in(file("."))
  .enablePlugins(PlayScala, PlayNettyServer)
  .disablePlugins(PlayAkkaHttpServer)
  .disablePlugins(PlayFilters)

sources in doc in Compile := List()
// disable publishing the main API jar
publishArtifact in (Compile, packageDoc) := false
// disable publishing the main sources jar
publishArtifact in (Compile, packageSrc) := false

scalacOptions ++= Seq(
  "-deprecation", "-unchecked", "-feature", "-language:_", "-Ydelambdafy:method"
)

val elastic4sVersion = "6.0.4"

libraryDependencies ++= Seq(
  "com.github.ornicar" %% "scalalib" % "6.5",
  "com.sksamuel.elastic4s" %% "elastic4s-core" % elastic4sVersion,
  "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion,
  "com.typesafe.play" %% "play-json-joda" % "2.6.7",
  "org.apache.logging.log4j" % "log4j-api" % "2.10.0",
  "org.apache.logging.log4j" % "log4j-core" % "2.10.0",
  ws,
  specs2 % Test
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator

Seq(
  ScalariformKeys.preferences := ScalariformKeys.preferences.value
    .setPreference(DanglingCloseParenthesis, Force)
    .setPreference(DoubleIndentConstructorArguments, true),
  excludeFilter in scalariformFormat := "*Routes*"
)
