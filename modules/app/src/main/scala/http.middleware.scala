package lila.search
package app

import cats.effect.IO
import org.http4s.*
import org.http4s.server.middleware.*
import org.typelevel.log4cats.LoggerFactory

import scala.concurrent.duration.*

type Middleware = HttpRoutes[IO] => HttpRoutes[IO]
def MkMiddleware(config: HttpServerConfig)(using LoggerFactory[IO]): Middleware =

  def verboseLogger =
    RequestLogger.httpRoutes[IO](true, true).andThen(ResponseLogger.httpRoutes[IO, Request[IO]](true, true))

  def logger =
    if config.apiLogger then verboseLogger
    else ApiErrorLogger.instance(using LoggerFactory[IO].getLogger)

  logger.andThen(AutoSlash(_)).andThen(Timeout(60.seconds))
