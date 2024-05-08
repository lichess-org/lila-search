package lila.search
package app

import cats.effect.IO
import org.http4s.*
import org.http4s.implicits.*
import org.http4s.server.middleware.*

import scala.concurrent.duration.*

type Middleware = HttpRoutes[IO] => HttpRoutes[IO]
def ApplyMiddleware(routes: HttpRoutes[IO]): HttpApp[IO] =

  val autoSlash: Middleware = AutoSlash(_)
  val timeout: Middleware   = Timeout(60.seconds)

  val middleware = autoSlash.andThen(timeout)

  middleware(routes).orNotFound
