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
  // watch change events from database and ingest documents into elastic search
  def watch: IO[Unit]
  // Similar to watch but started from a given timestamp
  def watch(since: Option[Instant], dryRun: Boolean): IO[Unit]
  // Fetch documents in [since, until] and ingest into elastic search
  def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit]

object Ingestor:

  given [A] => Schema[A] => Indexable[A] = a => writeToString(a)

  def apply[A: Schema](
      index: Index,
      repo: Repo[A],
      store: KVStore,
      elastic: ESClient[IO],
      defaultStartAt: Option[Instant]
  )(using LoggerFactory[IO]): Ingestor = new:
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")

    def watch: IO[Unit] =
      fs2.Stream
        .eval(startAt)
        .flatMap(repo.watch)
        .evalMap: result =>
          updateElastic(result, false) *> saveLastIndexedTimestamp(result.timestamp)
        .compile
        .drain

    def watch(since: Option[Instant], dryRun: Boolean): IO[Unit] =
      repo
        .watch(since)
        .evalMap(updateElastic(_, dryRun))
        .compile
        .drain

    def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit] =
      repo
        .fetch(since, until)
        .evalMap(updateElastic(_, dryRun))
        .compile
        .drain

    private def updateElastic(result: Repo.Result[A], dryRun: Boolean): IO[Unit] =
      dryRun.fold(
        info"Would index total ${result.toIndex.size} games and delete ${result.toDelete.size} games" *>
          result.toIndex.traverse_(x => debug"Would index $x")
          *> result.toDelete.traverse_(x => debug"Would delete $x"),
        storeBulk(index, result.toIndex)
          *> deleteMany(index, result.toDelete)
      )

    private def startAt: IO[Option[Instant]] =
      defaultStartAt
        .fold(store.get(index.value))(_.some.pure[IO])
        .flatTap(since => info"Starting ${index.value} ingestor from $since")

    private def deleteMany(index: Index, ids: List[Id]): IO[Unit] =
      allow:
        elastic.deleteMany(index, ids)
      .rescue: e =>
        logger.error(e.asException)(s"Failed to delete ${index.value}: ${ids.map(_.value).mkString(", ")}")
      .flatTap(_ => Logger[IO].info(s"Deleted ${ids.size} ${index.value}s"))
        .whenA(ids.nonEmpty)

    private def storeBulk(index: Index, sources: List[SourceWithId[A]]): IO[Unit] =
      Logger[IO].info(s"Received ${sources.size} docs to ${index.value}") *>
        allow:
          elastic.storeBulk(index, sources)
        .rescue: e =>
          logger.error(e.asException)(s"Failed to ${index.value} index: ${sources.map(_.id).mkString(", ")}")
        .whenA(sources.nonEmpty)
        *> logger.info(s"Indexed ${sources.size} ${index.value}s")

    private val saveLastIndexedTimestamp: Option[Instant] => IO[Unit] =
      _.traverse_(time =>
        store.put(index.value, time)
          *> Logger[IO].info(s"Stored last indexed time ${time.getEpochSecond} for ${index.value}")
      )
