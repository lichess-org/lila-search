package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

object Ingestors:

  import IndexRegistry.given

  def apply(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      store: KVStore,
      elastic: ESClient[IO],
      config: IngestorConfig
  )(using LoggerFactory[IO]): IO[Unit] =
    (
      ForumRepo(lichess, config.forum),
      UblogRepo(lichess, config.ublog),
      StudyRepo(study, local, config.study),
      GameRepo(lichess, config.game),
      TeamRepo(lichess, config.team)
    ).flatMapN: (forums, ublogs, studies, games, teams) =>
      List(
        watch(Index.Forum, forums, store, elastic, config.forum.startAt),
        watch(Index.Ublog, ublogs, store, elastic, config.ublog.startAt),
        watch(Index.Study, studies, store, elastic, config.study.startAt),
        watch(Index.Game, games, store, elastic, config.game.startAt),
        watch(Index.Team, teams, store, elastic, config.team.startAt)
      ).parSequence_

  // Watch mode with default start time (from store or config)
  def watch[A: Indexable](
      index: Index,
      repo: Repo[A],
      store: KVStore,
      elastic: ESClient[IO],
      defaultStartAt: Option[Instant]
  )(using LoggerFactory[IO]): IO[Unit] =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")
    val startAt: IO[Option[Instant]] =
      defaultStartAt
        .fold(store.get(index.value))(_.some.pure[IO])
        .flatTap(since => info"Starting ${index.value} ingestor from $since")
    fs2.Stream
      .eval(startAt)
      .flatMap(repo.watch)
      .evalMap(ElasticSink.updateElastic(index, elastic, store))
      .compile
      .drain
