package lila.search
package ingestor.game

import cats.effect.IO
import lila.search.clickhouse.ClickHouseClient
import lila.search.clickhouse.game.GameRow
import lila.search.ingestor.{ DbGame, Ingestor, Repo, Translate }
import org.typelevel.log4cats.{ Logger, LoggerFactory }

class CHGameIngestor(ch: ClickHouseClient[IO])(using LoggerFactory[IO]) extends Ingestor[DbGame]:

  private given Logger[IO] = LoggerFactory[IO].getLoggerFromName("game.ingestor")

  def ingest(stream: fs2.Stream[IO, Repo.Result[DbGame]]): IO[Unit] =
    stream
      .evalMap: result =>
        Logger[IO].info(s"Updating ${result.toIndex.size} docs to game") *>
          ch.upsertGameRows(result.toIndex.map(toRow)) *>
          ch.deleteGames(result.toDelete.map(_.value))
      .compile
      .drain

  private def toRow(g: DbGame): GameRow =
    Translate.toGameRow(g)
