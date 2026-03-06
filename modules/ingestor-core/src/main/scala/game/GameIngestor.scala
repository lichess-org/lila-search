package lila.search
package ingestor.game

import cats.effect.IO
import lila.search.clickhouse.ClickHouseClient
import lila.search.ingestor.*
import lila.search.ingestor.given
import org.typelevel.log4cats.LoggerFactory

object GameIngestor:

  def apply(
      backend: GameIngestBackend,
      elastic: ESClient[IO],
      clickhouse: ClickHouseClient[IO],
      botCache: BotUserCache
  )(using LoggerFactory[IO]): Ingestor[DbGame] =
    backend match
      case GameIngestBackend.Elastic => ESIngestor(Index.Game, elastic)
      case GameIngestBackend.ClickHouse => CHGameIngestor(clickhouse, botCache)
      case GameIngestBackend.Both =>
        val es = ESIngestor[DbGame](Index.Game, elastic)
        val ch = CHGameIngestor(clickhouse, botCache)
        new Ingestor[DbGame]:
          def ingest(stream: fs2.Stream[IO, Repo.Result[DbGame]]): IO[Unit] =
            stream
              .broadcastThrough(es.ingestPipe, ch.ingestPipe)
              .compile
              .drain
