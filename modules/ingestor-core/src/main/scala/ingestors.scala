package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import lila.search.clickhouse.ClickHouseClient
import lila.search.ingestor.game.GameIngestor
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

object Ingestors:

  def apply(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      store: KVStore,
      elastic: ESClient[IO],
      clickhouse: ClickHouseClient[IO],
      config: IngestorConfig,
      gameIngestBackend: GameIngestBackend,
      botCache: BotUserCache
  )(using LoggerFactory[IO]): IO[Unit] =
    (
      ForumRepo(lichess, config.forum),
      // UblogRepo(lichess, config.ublog),
      StudyRepo(study, local, config.study),
      GameRepo(lichess, config.game),
      TeamRepo(lichess, config.team)
    ).flatMapN: (forums, study2s, games, teams) =>
      given KVStore = store
      List(
        run(Index.Forum, forums, ESIngestor(Index.Forum, elastic), config.forum.startAt),
        // run(Index.Ublog, ublogs, ESIngestor(Index.Ublog, elastic), config.ublog.startAt),
        run(Index.Study, study2s, ESIngestor(Index.Study, elastic), config.study.startAt),
        run(Index.Team, teams, ESIngestor(Index.Team, elastic), config.team.startAt),
        run(
          Index.Game,
          games,
          GameIngestor(gameIngestBackend, elastic, clickhouse, botCache),
          config.game.startAt
        )
      ).parSequence_

  private def run[A](
      index: Index,
      repo: Repo[A],
      ingestor: Ingestor[A],
      defaultStartAt: Option[Instant]
  )(using LoggerFactory[IO], KVStore): IO[Unit] =
    given Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")
    val startAt: IO[Option[Instant]] =
      defaultStartAt
        .fold(summon[KVStore].get(index.value))(_.some.pure[IO])
        .flatTap(since => info"Starting ${index.value} ingestor from $since")
    val stream = fs2.Stream
      .eval(startAt)
      .flatMap(repo.watch)
      .evalTap(result => result.timestamp.traverse_(saveTimestamp(index)))
    ingestor.ingest(stream)

  private def saveTimestamp(index: Index)(time: Instant)(using Logger[IO])(using store: KVStore): IO[Unit] =
    store.put(index.value, time) *>
      Logger[IO].info(s"Stored last indexed time ${time.getEpochSecond} for ${index.value}")
