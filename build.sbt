import Dependencies.*
import org.typelevel.scalacoptions.ScalacOptions

inThisBuild(
  Seq(
    scalaVersion  := "3.5.2",
    versionScheme := Some("early-semver"),
    organization  := "org.lichess.search",
    run / fork    := true,
    run / javaOptions += "-Dconfig.override_with_env_vars=true",
    semanticdbEnabled := true, // for scalafix
    resolvers ++= ourResolvers,
    Compile / doc / sources := Seq.empty,
    publishTo               := Option(Resolver.file("file", new File(sys.props.getOrElse("publishTo", ""))))
  )
)

val commonSettings = Seq(
  tpolecatScalacOptions ++= Set(
    ScalacOptions.sourceFuture,
    ScalacOptions.other("-rewrite"),
    ScalacOptions.other("-indent"),
    ScalacOptions.explain,
    ScalacOptions.release("21"),
    ScalacOptions.other("-Wsafe-init") // fix in: https://github.com/typelevel/scalac-options/pull/136
  ),
  libraryDependencies += compilerPlugin("com.github.ghik" % "zerowaste" % "0.2.26" cross CrossVersion.full)
)

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "core",
    commonSettings,
    libraryDependencies ++= Seq(
      catsCore,
      smithy4sCore
    )
  )

lazy val elastic = project
  .in(file("modules/elastic"))
  .settings(
    name := "elastic",
    commonSettings,
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      elastic4sJavaClient,
      elastic4sCatsEffect,
      otel4sCore
    )
  )
  .dependsOn(core)

lazy val api = project
  .in(file("modules/api"))
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
  .dependsOn(core)

lazy val ingestor = project
  .in(file("modules/ingestor"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "ingestor",
    commonSettings,
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(
      chess,
      catsCore,
      fs2,
      fs2IO,
      catsEffect,
      declineCore,
      declineCatsEffect,
      ducktape,
      cirisCore,
      smithy4sCore,
      smithy4sJson,
      jsoniterCore,
      jsoniterMacro,
      circe,
      http4sServer,
      mongo4catsCore,
      mongo4catsCirce,
      log4Cats,
      logback,
      otel4sMetricts,
      otel4sSdk,
      otel4sPrometheusExporter,
      weaver,
      weaverScalaCheck,
      testContainers
    ),
    Compile / run / fork := true
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(elastic, core)

lazy val client = project
  .in(file("modules/client"))
  .settings(
    name := "client",
    commonSettings,
    libraryDependencies ++= Seq(
      smithy4sJson,
      jsoniterCore,
      jsoniterMacro,
      playWS
    )
  )
  .dependsOn(api, core)

lazy val app = project
  .in(file("modules/app"))
  .settings(
    name := "lila-search",
    commonSettings,
    publish        := {},
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
      logback,
      otel4sMetricts,
      otel4sSdk,
      otel4sPrometheusExporter,
      weaver,
      testContainers
    ),
    Compile / run / fork := true
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(api, elastic)

val e2e = project
  .in(file("modules/e2e"))
  .settings(
    publish        := {},
    publish / skip := true,
    libraryDependencies ++= Seq(weaver)
  )
  .dependsOn(client, app)

lazy val root = project
  .in(file("."))
  .settings(publish := {}, publish / skip := true)
  .aggregate(core, api, app, client, e2e, elastic, ingestor)

addCommandAlias("prepare", "scalafixAll; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check ; scalafmtCheckAll")

