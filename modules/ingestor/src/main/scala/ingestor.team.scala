package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

trait TeamIngestor:
  // watch change events from MongoDB and ingest team data into elastic search
  def watch: IO[Unit]
  // Fetch teams in [since, until] and ingest into elastic search
  def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit]

object TeamIngestor:

  private val index = Index.Team

  def apply(teams: Teams, elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Team)(using
      LoggerFactory[IO]
  ): TeamIngestor = new:
    given Logger[IO] = summon[LoggerFactory[IO]].getLogger
    def watch =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting team ingestor from $since"))
        .flatMap: last =>
          teams
            .watch(last)
            .evalMap: result =>
              storeBulk(result.toIndex)
                *> elastic.deleteMany(index, result.toDelete)
                *> saveLastIndexedTimestamp(result.timestamp.getOrElse(Instant.now))
        .compile
        .drain

    def run(since: Instant, until: Instant, dryRun: Boolean) =
      teams
        .fetch(since, until)
        .evalMap: result =>
          dryRun.fold(
            result.toIndex.traverse_(doc => debug"Would index $doc")
              *> result.toDelete.traverse_(doc => debug"Would delete $doc"),
            storeBulk(result.toIndex) *> elastic.deleteMany(index, result.toDelete)
          )
        .compile
        .drain

    private def storeBulk(sources: List[Teams.SourceWithId]): IO[Unit] =
      info"Received ${sources.size} teams to index" *>
        elastic
          .storeBulk(index, sources)
          .handleErrorWith: e =>
            Logger[IO].error(e)(s"Failed to index teams: ${sources.map(_._1).mkString(", ")}")
          .whenA(sources.nonEmpty)
        *> info"Indexed ${sources.size} teams"

    private def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(_.some.pure[IO])
