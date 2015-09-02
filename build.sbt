name := """lila-search"""

version := "1.0"

lazy val chess = project in file("chess")

lazy val root = project in file(".") enablePlugins PlayScala dependsOn chess settings (
  sources in doc in Compile := List(),
  // disable publishing the main API jar
  publishArtifact in (Compile, packageDoc) := false,
  // disable publishing the main sources jar
  publishArtifact in (Compile, packageSrc) := false)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.github.ornicar" %% "scalalib" % "5.3",
  "com.sksamuel.elastic4s" %% "elastic4s-core" % "1.7.0",
  cache,
  ws,
  specs2 % Test
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator
