package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

trait StudyIngestor:
  // pull changes from study MongoDB and ingest into elastic search
  def watch: IO[Unit]
  def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit]

object StudyIngestor:

  private val index = Index.Study

  def apply(
      studies: Studies,
      store: KVStore,
      config: IngestorConfig.Study
  )(using LoggerFactory[IO], ESClient[IO]): StudyIngestor = new:
    given Logger[IO] = LoggerFactory[IO].getLogger
    def watch: IO[Unit] =
      fs2.Stream
        .eval(startAt)
        .flatMap(studies.watch)
        .evalMap: result =>
          storeBulk(index, result.toIndex)
            *> deleteMany(index, result.toDelete)
            *> store.saveLastIndexedTimestamp(index, result.timestamp)
        .compile
        .drain

    def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit] =
      studies
        .fetch(since, until)
        .evalMap: result =>
          dryRun.fold(
            result.toIndex.traverse_(doc => debug"Would index $doc")
              *> result.toDelete.traverse_(doc => debug"Would delete $doc"),
            storeBulk(index, result.toIndex) *> deleteMany(index, result.toDelete)
          )
        .compile
        .drain

    private def startAt: IO[Option[Instant]] =
      config.startAt
        .fold(store.get(index.value))(_.some.pure[IO])
        .flatTap(since => info"Starting forum ingestor from $since")
