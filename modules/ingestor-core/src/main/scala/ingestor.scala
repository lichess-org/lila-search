package lila.search
package ingestor

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sksamuel.elastic4s.Indexable
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import smithy4s.json.Json.given
import smithy4s.schema.Schema

import java.time.Instant

trait Ingestor:
  def run(): IO[Unit]

object Ingestor:

  given [A] => Schema[A] => Indexable[A] = a => writeToString(a)

  given Indexable[DbGame] = a => writeToString(Translate.game(a))
  given Indexable[DbForum] = a => writeToString(Translate.forum(a))
  given Indexable[DbUblog] = a => writeToString(Translate.ublog(a))
  given Indexable[(DbStudy, StudyChapterData)] = a => writeToString(Translate.study.tupled(a))
  given Indexable[DbTeam] = a => writeToString(Translate.team(a))

  // Watch mode with default start time (from store or config)
  def watch[A: Indexable](
      index: Index,
      repo: Repo[A],
      store: KVStore,
      elastic: ESClient[IO],
      defaultStartAt: Option[Instant]
  )(using LoggerFactory[IO]): Ingestor =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")

    new:
      def run(): IO[Unit] =
        val startAt: IO[Option[Instant]] =
          defaultStartAt
            .fold(store.get(index.value))(_.some.pure[IO])
            .flatTap(since => info"Starting ${index.value} ingestor from $since")

        fs2.Stream
          .eval(startAt)
          .flatMap(repo.watch)
          .evalMap: result =>
            updateElastic(index, elastic, result, false) *>
              saveLastIndexedTimestamp(index, store, result.timestamp)
          .compile
          .drain

  def index[A: Indexable](
      index: Index,
      repo: Repo[A],
      store: KVStore,
      elastic: ESClient[IO],
      since: Instant,
      until: Instant,
      watch: Boolean,
      dryRun: Boolean
  )(using LoggerFactory[IO]): Ingestor =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")
    new:
      def run(): IO[Unit] =
        val stream =
          if watch then repo.watch(since.some)
          else repo.fetch(since, until)
        stream
          .evalMap: result =>
            updateElastic(index, elastic, result, dryRun) *>
              saveLastIndexedTimestamp(index, store, result.timestamp)
          .compile
          .drain

  private def updateElastic[A: Indexable](
      index: Index,
      elastic: ESClient[IO],
      result: Repo.Result[A],
      dryRun: Boolean
  )(using logger: Logger[IO]): IO[Unit] =
    dryRun.fold(
      info"Would index total ${result.toIndex.size} documents and delete ${result.toDelete.size} documents" *>
        result.toIndex.traverse_(x => debug"Would index $x") *>
        result.toDelete.traverse_(x => debug"Would delete $x"),
      storeBulk(index, elastic, result.toIndex) *>
        deleteMany(index, elastic, result.toDelete)
    )

  private def deleteMany(
      index: Index,
      elastic: ESClient[IO],
      ids: List[Id]
  )(using logger: Logger[IO]): IO[Unit] =
    allow:
      elastic.deleteMany(index, ids)
    .rescue: e =>
      logger.error(e.asException)(s"Failed to delete ${index.value}: ${ids.map(_.value).mkString(", ")}")
    .flatTap(_ => Logger[IO].info(s"Deleted ${ids.size} ${index.value}s"))
      .whenA(ids.nonEmpty)

  private def storeBulk[A: Indexable](
      index: Index,
      elastic: ESClient[IO],
      sources: List[SourceWithId[A]]
  )(using logger: Logger[IO]): IO[Unit] =
    Logger[IO].info(s"Received ${sources.size} docs to ${index.value}") *>
      allow:
        elastic.storeBulk(index, sources)
      .rescue: e =>
        logger.error(e.asException)(s"Failed to ${index.value} index: ${sources.map(_.id).mkString(", ")}")
      .whenA(sources.nonEmpty) *>
      logger.info(s"Indexed ${sources.size} ${index.value}s")

  private def saveLastIndexedTimestamp(
      index: Index,
      store: KVStore,
      timestamp: Option[Instant]
  )(using Logger[IO]): IO[Unit] =
    timestamp.traverse_(time =>
      store.put(index.value, time) *>
        Logger[IO].info(s"Stored last indexed time ${time.getEpochSecond} for ${index.value}")
    )
