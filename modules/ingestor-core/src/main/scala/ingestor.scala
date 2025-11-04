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

// Single-method interface: creates an ingestion task that runs when run() is called
trait Ingestor:
  def run(): IO[Unit]

object Ingestor:

  given [A] => Schema[A] => Indexable[A] = a => writeToString(a)

  // Watch mode with default start time (from store or config)
  def watch[A: Schema, B](
      index: Index,
      repo: Repo[B],
      translate: B => A,
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
            val translated = result.map(translate)
            updateElastic(index, elastic, translated, false) *>
              saveLastIndexedTimestamp(index, store, result.timestamp)
          .compile
          .drain

  // Watch mode with specific start time
  def watch[A: Schema, B](
      index: Index,
      repo: Repo[B],
      translate: B => A,
      store: KVStore,
      elastic: ESClient[IO],
      since: Option[Instant],
      dryRun: Boolean
  )(using LoggerFactory[IO]): Ingestor =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")

    new:
      def run(): IO[Unit] =
        repo
          .watch(since)
          .evalMap: result =>
            updateElastic(index, elastic, result.map(translate), dryRun) *>
              saveLastIndexedTimestamp(index, store, result.timestamp)
          .compile
          .drain

  // Batch indexing mode
  def index[A: Schema, B](
      index: Index,
      repo: Repo[B],
      translate: B => A,
      store: KVStore,
      elastic: ESClient[IO],
      since: Instant,
      until: Instant,
      dryRun: Boolean
  )(using LoggerFactory[IO]): Ingestor =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")

    new:
      def run(): IO[Unit] =
        repo
          .fetch(since, until)
          .evalMap: result =>
            updateElastic(index, elastic, result.map(translate), dryRun) *>
              saveLastIndexedTimestamp(index, store, result.timestamp)
          .compile
          .drain

  // Helper methods
  private def updateElastic[A: Schema](
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

  private def storeBulk[A: Schema](
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
