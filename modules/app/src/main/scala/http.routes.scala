package lila.search
package app

import cats.data.NonEmptyList
import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import lila.search.spec.*
import org.http4s.HttpRoutes
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.metrics.MeterProvider
import smithy4s.http4s.SimpleRestJsonBuilder

def Routes(
    resources: AppResources,
    config: AppConfig
)(using LoggerFactory[IO], MeterProvider[IO]): Resource[IO, HttpRoutes[IO]] =

  val healthServiceImpl = HealthServiceImpl(resources.esClient)

  def searchService: IO[SearchServiceImpl] =
    val dualMetrics = config.gameBackend match
      case _: GameSearchBackend.Dual => DualMetrics.make
      case _ => IO.pure(DualMetrics.noop)
    (GameMetrics.make, dualMetrics).mapN:
      SearchServiceImpl(resources.esClient, resources.chClient, config.gameBackend, _, _)

  val search: Resource[IO, HttpRoutes[IO]] =
    Resource.eval(searchService).flatMap(svc => SimpleRestJsonBuilder.routes(svc).resource)

  val health: Resource[IO, HttpRoutes[IO]] =
    SimpleRestJsonBuilder.routes(healthServiceImpl).resource

  def docs = smithy4s.http4s.swagger.docs[IO](SearchService, HealthService)

  val apiRoutes =
    NonEmptyList
      .of(search, health)
      .sequence
      .map(_.reduceK)

  val allRoutes =
    if config.server.enableDocs then apiRoutes.map(_ <+> docs)
    else apiRoutes

  allRoutes.evalMap(routes => MkMiddleware(config.server).map(md => md.apply(routes)))
