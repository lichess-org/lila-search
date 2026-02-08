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
      // study: MongoDatabase[IO],
      // local: MongoDatabase[IO],
      store: KVStore,
      elastic: ESClient[IO],
      config: IngestorConfig
  )(using LoggerFactory[IO]): IO[Unit] =
    (
      ForumRepo(lichess, config.forum),
      UblogRepo(lichess, config.ublog),
      // StudyRepo(study, local, config.study),
      GameRepo(lichess, config.game),
      TeamRepo(lichess, config.team)
    ).flatMapN: (forums, ublogs, games, teams) =>
      given KVStore = store
      given ESClient[IO] = elastic
      List(
        watch(Index.Forum, forums, config.forum.startAt),
        watch(Index.Ublog, ublogs, config.ublog.startAt),
        // watch(Index.Study, study2s, config.study.startAt),
        watch(Index.Game, games, config.game.startAt),
        watch(Index.Team, teams, config.team.startAt)
      ).parSequence_

  // Watch mode with default start time (from store or config)
  def watch[A: Indexable: HasStringId](
      index: Index,
      repo: Repo[A],
      defaultStartAt: Option[Instant]
  )(using lf: LoggerFactory[IO], store: KVStore, elastic: ESClient[IO]): IO[Unit] =
    given logger: Logger[IO] = lf.getLoggerFromName(s"${index.value}.ingestor")
    val startAt: IO[Option[Instant]] =
      defaultStartAt
        .fold(store.get(index.value))(_.some.pure[IO])
        .flatTap(since => info"Starting ${index.value} ingestor from $since")
    fs2.Stream
      .eval(startAt)
      .flatMap(repo.watch)
      .evalMap(index.updateElastic)
      .compile
      .drain
