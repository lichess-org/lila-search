package lila.search
package app

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import lila.search.spec.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

class HealthServiceImpl(esClient: ESClient[IO])(using LoggerFactory[IO]) extends HealthService[IO]:

  given Logger[IO] = LoggerFactory[IO].getLogger

  override def healthCheck(): IO[HealthCheckOutput] =
    allow:
      esClient.status
        .flatMap(transform)
        .map(HealthCheckOutput(_))
    .rescue: e =>
      Logger[IO].error(e.asException)("Error in health check") *>
        IO.raiseError(InternalServerError(s"Internal server error ${e.reason}"))

  private def transform(status: String): IO[ElasticStatus] =
    status match
      case "green" => ElasticStatus.green.pure[IO]
      case "yellow" => ElasticStatus.yellow.pure[IO]
      case "red" => ElasticStatus.red.pure[IO]
      case _ => IO.raiseError(new Exception(s"Unknown status: $status"))
