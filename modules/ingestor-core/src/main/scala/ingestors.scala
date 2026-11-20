package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import lila.search.ingestor.game.ESGameIngestor
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
      config: IngestorConfig,
      botCache: BotUserCache
  )(using LoggerFactory[IO]): IO[Unit] =
    (
      ForumRepo(lichess, config.forum),
      UblogRepo(lichess, config.ublog),
      UblogRepo.blogTierRepo(lichess, config.ublog),
      StudyRepo(study, local, config.study),
      GameRepo(lichess, config.game),
      TeamRepo(lichess, config.team)
    ).flatMapN: (forums, ublogs, blogTiers, study2s, games, teams) =>
      given KVStore = store
      List(
        run(forums, ESIngestor(Index.Forum, elastic), config.forum.startAt, Index.Forum.value),
        run(ublogs, ESIngestor(Index.Ublog, elastic), config.ublog.startAt, Index.Ublog.value),
        // blog tier changes do not emit post events, so they need their own checkpoint
        run(blogTiers, ESIngestor(Index.Ublog, elastic), config.ublog.startAt, "ublog-blog"),
        run(study2s, ESIngestor(Index.Study, elastic), config.study.startAt, Index.Study.value),
        run(teams, ESIngestor(Index.Team, elastic), config.team.startAt, Index.Team.value),
        run(games, ESGameIngestor(elastic, botCache), config.game.startAt, Index.Game.value)
      ).parSequence_

  private def run[A](
      repo: Repo[A],
      ingestor: Ingestor[A],
      defaultStartAt: Option[Instant],
      key: String
  )(using LoggerFactory[IO], KVStore): IO[Unit] =
    given Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"$key.ingestor")
    val startAt: IO[Option[Instant]] =
      defaultStartAt
        .fold(summon[KVStore].get(key))(_.some.pure[IO])
        .flatTap(since => info"Starting $key ingestor from $since")
    val stream = fs2.Stream
      .eval(startAt)
      .flatMap(repo.watch)
      .evalTap(result => result.timestamp.traverse_(saveTimestamp(key)))
    ingestor.ingest(stream)

  private def saveTimestamp(key: String)(time: Instant)(using Logger[IO])(using store: KVStore): IO[Unit] =
    store.put(key, time) *>
      Logger[IO].info(s"Stored last indexed time ${time.getEpochSecond} for $key")
