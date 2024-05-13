package lila.search
package app

import cats.data.NonEmptyList
import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import lila.search.spec.*
import org.http4s.{ HttpApp, HttpRoutes }
import org.typelevel.log4cats.Logger
import smithy4s.http4s.SimpleRestJsonBuilder

def Routes(resources: AppResources, config: HttpServerConfig)(using Logger[IO]): Resource[IO, HttpApp[IO]] =

  val healthServiceImpl: HealthService[IO] = new HealthService.Default[IO](IO.stub)

  val searchServiceImpl: SearchService[IO] = SearchServiceImpl(resources.esClient)

  val search: Resource[IO, HttpRoutes[IO]] =
    SimpleRestJsonBuilder.routes(searchServiceImpl).resource

  val health: Resource[IO, HttpRoutes[IO]] =
    SimpleRestJsonBuilder.routes(healthServiceImpl).resource

  val docs = smithy4s.http4s.swagger.docs[IO](SearchService, HealthService)

  val apiRoutes =
    NonEmptyList
      .of(search, health)
      .sequence
      .map(_.reduceK)

  val allRoutes =
    if config.enableDocs then apiRoutes.map(_ <+> docs)
    else apiRoutes

  allRoutes.map(ApplyMiddleware(config))
