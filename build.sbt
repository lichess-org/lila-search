name := """lila-search"""

version := "1.1"

lazy val chess = project in file("chess")

lazy val root = project in file(".") enablePlugins PlayScala dependsOn chess settings (
  sources in doc in Compile := List(),
  // disable publishing the main API jar
  publishArtifact in (Compile, packageDoc) := false,
  // disable publishing the main sources jar
  publishArtifact in (Compile, packageSrc) := false)

scalaVersion := "2.11.8"

scalacOptions ++= Seq(
  "-deprecation", "-unchecked", "-feature", "-language:_",
  "-Ybackend:GenBCode", "-Ydelambdafy:method", "-target:jvm-1.8")

libraryDependencies ++= Seq(
  "com.github.ornicar" %% "scalalib" % "5.4",
  "com.sksamuel.elastic4s" %% "elastic4s-core" % "2.2.0",
  "org.scala-lang.modules" %% "scala-java8-compat" % "0.7.0",
  cache,
  ws,
  specs2 % Test
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator
