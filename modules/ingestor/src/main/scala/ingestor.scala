package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }
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

  def apply[A: Schema](index: Index, games: Repo[A], store: KVStore, defaultStartAt: Option[Instant])(using
      LoggerFactory[IO],
      ESClient[IO]
  ): Ingestor = new:
    given Logger[IO] = LoggerFactory[IO].getLogger

    def watch: IO[Unit] =
      fs2.Stream
        .eval(startAt)
        .flatMap(games.watch)
        .evalMap: result =>
          updateElastic(result, false) *> store.saveLastIndexedTimestamp(index, result.timestamp)
        .compile
        .drain

    def watch(since: Option[Instant], dryRun: Boolean): IO[Unit] =
      games
        .watch(since)
        .evalMap(updateElastic(_, dryRun))
        .compile
        .drain

    def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit] =
      games
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
