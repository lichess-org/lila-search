package lila.search
package app

import cats.effect.IO
import org.http4s.*
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.server.middleware.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.metrics.MeterProvider

import scala.concurrent.duration.*

type Middleware = HttpRoutes[IO] => HttpRoutes[IO]
def MkMiddleware(config: HttpServerConfig)(using LoggerFactory[IO], MeterProvider[IO]): IO[Middleware] =

  def verboseLogger =
    RequestLogger.httpRoutes[IO](true, true).andThen(ResponseLogger.httpRoutes[IO, Request[IO]](true, true))

  def logger =
    if config.apiLogger then verboseLogger
    else ApiErrorLogger.instance(using LoggerFactory[IO].getLogger)

  OtelMetrics
    .serverMetricsOps[IO]()
    .map(org.http4s.server.middleware.Metrics[IO](_, None))
    .map: metrics =>
      logger.andThen(AutoSlash(_)).andThen(Timeout(60.seconds)).andThen(metrics)
