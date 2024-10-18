package lila.search
package app

import cats.effect.IO
import org.http4s.*
import org.http4s.implicits.*
import org.http4s.server.middleware.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import scala.concurrent.duration.*

type Middleware = HttpRoutes[IO] => HttpRoutes[IO]
def ApplyMiddleware(config: HttpServerConfig)(routes: HttpRoutes[IO])(using LoggerFactory[IO]): HttpApp[IO] =

  val autoSlash: Middleware = AutoSlash(_)
  val timeout: Middleware   = Timeout(60.seconds)

  val middleware = autoSlash.andThen(timeout)

  def verboseLogger =
    RequestLogger.httpApp[IO](true, true).andThen(ResponseLogger.httpApp[IO, Request[IO]](true, true))

  given Logger[IO] = LoggerFactory[IO].getLogger
  val logger =
    if config.apiLogger then verboseLogger
    else ApiErrorLogger.instance

  logger(middleware(routes).orNotFound)
