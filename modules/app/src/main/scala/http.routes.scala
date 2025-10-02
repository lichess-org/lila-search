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
    config: HttpServerConfig
)(using LoggerFactory[IO], MeterProvider[IO]): Resource[IO, HttpRoutes[IO]] =

  val healthServiceImpl = HealthServiceImpl(resources.esClient)
  val searchServiceImpl = SearchServiceImpl(resources.esClient)

  val search: Resource[IO, HttpRoutes[IO]] =
    SimpleRestJsonBuilder.routes(searchServiceImpl).resource

  val health: Resource[IO, HttpRoutes[IO]] =
    SimpleRestJsonBuilder.routes(healthServiceImpl).resource

  def docs = smithy4s.http4s.swagger.docs[IO](SearchService, HealthService)

  val apiRoutes =
    NonEmptyList
      .of(search, health)
      .sequence
      .map(_.reduceK)

  val allRoutes =
    if config.enableDocs then apiRoutes.map(_ <+> docs)
    else apiRoutes

  allRoutes.evalMap(routes => MkMiddleware(config).map(md => md.apply(routes)))
