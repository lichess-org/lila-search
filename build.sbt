import org.typelevel.scalacoptions.{ ScalacOption, ScalacOptions }
import Dependencies.*

inThisBuild(
  Seq(
    scalaVersion  := "3.4.2",
    versionScheme := Some("early-semver"),
    version       := "3.0.0-RC9",
    organization  := "org.lichess.search",
    run / fork    := true,
    run / javaOptions += "-Dconfig.override_with_env_vars=true",
    semanticdbEnabled       := true, // for scalafix
    Compile / doc / sources := Seq.empty,
    publishTo               := Option(Resolver.file("file", new File(sys.props.getOrElse("publishTo", ""))))
  )
)

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name           := "lila-search-core",
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      elastic4sJavaClient,
      catsCore,
      "joda-time" % "joda-time" % "2.12.7"
    )
  )

lazy val api = (project in file("modules/api"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name                     := "api",
    smithy4sWildcardArgument := "?",
    libraryDependencies ++= Seq(
      catsCore,
      smithy4sCore
    )
  )

lazy val ingestor = (project in file("modules/ingestor"))
  .settings(
    name           := "ingestor",
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      elastic4sCatsEffect,
      catsCore,
      catsEffect,
      ducktape,
      cirisCore,
      mongo4catsCore,
      log4Cats,
      logback,
      weaver,
      testContainers,
    ),
    Compile / run / fork := true
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(core)


lazy val client = (project in file("modules/client"))
  .settings(
    name := "client",
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
      logback,
      weaver,
      testContainers
    ),
    Compile / run / fork := true
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(api, core)

val e2e = (project in file("modules/e2e"))
  .settings(
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(weaver)
  )
  .dependsOn(client, app)

lazy val root = project
  .in(file("."))
  .settings(publish := {}, publish / skip := true)
  .aggregate(core, api, app, client, e2e, ingestor)

addCommandAlias("prepare", "scalafixAll; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check ; scalafmtCheckAll")
