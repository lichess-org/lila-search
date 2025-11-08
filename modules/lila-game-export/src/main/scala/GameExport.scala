package lila.search
package game.csv

import cats.effect.IO
import cats.syntax.all.*
import fs2.data.csv.*
import fs2.io.file.{ Files, Path }
import lila.search.ingestor.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

object GameExport:

  given CsvRowEncoder[DbGame, String] = CsvRowEncoder[GameCsv, String].contramap(GameCsv.fromDbGame)

  def apply(
      mongo: MongoDatabaseConfig,
      config: IngestorConfig.Game,
      output: String,
      since: Instant,
      until: Instant,
      watch: Boolean
  )(using LoggerFactory[IO]): IO[Unit] =
    mongo.makeClient.use: db =>
      GameRepo(db, config).flatMap: repo =>
        apply(repo, output, since, until, watch)

  def apply(
      repo: Repo[DbGame],
      output: String,
      since: Instant,
      until: Instant,
      watch: Boolean
  )(using LoggerFactory[IO]): IO[Unit] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    val mode = if watch then "watch mode" else s"from ${since.toString} to ${until.toString}"
    Logger[IO].info(s"Exporting games in $mode to $output") *>
      exportGames(repo, output, since, until, watch)

  def csvSink(output: Path): fs2.Pipe[IO, DbGame, Unit] =
    _.through(encodeUsingFirstHeaders(fullRows = true))
      .intersperse("\n")
      .through(fs2.text.utf8.encode)
      .through(Files[IO].writeAll(output))

  private def exportGames(
      repo: Repo[DbGame],
      output: String,
      since: Instant,
      until: Instant,
      watch: Boolean
  )(using Logger[IO]): IO[Unit] =
    val stream = if watch then repo.watch(since.some) else repo.fetch(since, until)
    Logger[IO].info(s"Starting export of games to $output") *>
      stream
        .flatMap: result =>
          fs2.Stream.emits(result.toIndex)
        .through(csvSink(Path(output)))
        .compile
        .drain
