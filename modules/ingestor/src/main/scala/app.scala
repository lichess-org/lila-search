package lila.search
package ingestor

import cats.effect.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object App extends IOApp.Simple:

  given Logger[IO] = Slf4jLogger.getLogger[IO]

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      config <- AppConfig.load.toResource
      _      <- Logger[IO].info(s"Starting lila-search ingestor with config: $config").toResource
      res    <- AppResources.instance(config)
      _      <- IngestorApp(res).run()
    yield ()

class IngestorApp(res: AppResources)(using Logger[IO]):
  val ingestor = Ingestor(res.mongo, res.elastic)
  def run(): Resource[IO, Unit] =
    ingestor
      .run()
      .toResource
      .evalTap(_ => Logger[IO].info("Ingestor started"))
