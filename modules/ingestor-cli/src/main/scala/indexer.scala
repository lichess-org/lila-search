package lila.search
package ingestor

import cats.effect.IO
import cats.effect.std.Supervisor
import cats.mtl.Handle
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import fs2.io.file.Files
import lila.search.ingestor.opts.{ IndexOpts, ReindexOpts }
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant
import scala.concurrent.duration.*

object Indexer:

  type IndexOrAll = Index | Unit
  extension (index: IndexOrAll)
    inline def toList: List[Index] = index match
      case i: Index => List(i)
      case _ => Index.values.toList

  extension (index: Index) def deletedAtLogPath: String = s"./deletes_${index.value}.log"

class Indexer(val res: AppResources, val config: AppConfig)(using LoggerFactory[IO]):
  import Indexer.*

  given logger: Logger[IO] = LoggerFactory[IO].getLogger
  given registry: IndexRegistry = IndexRegistry(
    GameRepo(res.lichess, config.ingestor.game),
    ForumRepo(res.lichess, config.ingestor.forum),
    UblogRepo(res.lichess, config.ingestor.ublog),
    StudyRepo(res.study, res.studyLocal, config.ingestor.study),
    Study2Repo(res.study, res.studyLocal, config.ingestor.study),
    TeamRepo(res.lichess, config.ingestor.team)
  )
  given KVStore = res.store
  given ESClient[IO] = res.elastic

  def index(opts: IndexOpts): IO[Unit] =

    def go(index: Index) =
      putMappingsIfNotExists(res.elastic, index).whenA(!opts.dry) *>
        runIndex(index, opts) *>
        refreshIndexes(res.elastic, index).whenA(opts.refresh && !opts.dry)

    opts.index.toList.traverse_(go)

  def reindex(opts: ReindexOpts) =
    def go(index: Index) =
      Handle
        .allow:
          res.elastic.putMapping(index)
        .rescue: e =>
          Logger[IO].error(e.asException)(s"Failed put mapping for ${index.value}") *>
            e.asException.raiseError
      *>
        runReindex(index, opts) *>
        refreshIndexes(res.elastic, index).whenA(!opts.dry)
    if opts.index != Index.Study2 then
      logger.warn(
        s"Reindexing is only supported for the Study2 index. No action taken for ${opts.index.toString}."
      )
    else opts.index.toList.traverse_(go)

  def runIndex(index: Index, opts: IndexOpts): IO[Unit] =
    given logger: Logger[IO] = LoggerFactory.getLoggerFromName(s"${index.value}.ingestor")
    val im = registry(index)
    im.withRepo: repo =>
      val stream =
        if opts.watch then repo.watch(opts.since.some)
        else repo.fetchAll(opts.since, opts.until)
      val f: Repo.Result[im.Out] => IO[Unit] =
        if opts.dry then
          result =>
            result.toIndex.traverse_(item => Logger[IO].info(s"Dry run - would index ${item.id}")) *>
              result.toDelete.traverse_(id => Logger[IO].info(s"Dry run - would delete ${id.value}"))
        else index.updateElastic

      stream
        .evalMap(f)
        .compile
        .drain

  /**
   * Reindex all documents in the index, while handling deletions that occur during the process.
   *
   * Reindexing process:
   *   1. Fetch all documents from the source and index them
   *   2. Start a background process to log deleted IDs every hour
   *   3. After indexing is complete, read the deleted IDs log and delete those from the index
   */
  def runReindex(
      index: Index,
      opts: ReindexOpts
  ): IO[Unit] =
    import opts.{ since, until, collectDeletionInterval, dry }

    def store_[A: Indexable: HasStringId](sources: List[A]): IO[Unit] =
      if dry then Logger[IO].info(s"Dry run - would index ${sources.size} docs to ${index.value}")
      else index.storeBulk(sources)

    def delete_(ids: List[Id]): IO[Unit] =
      if dry then Logger[IO].info(s"Dry run - would delete ${ids.size} docs from ${index.value}")
      else index.deleteMany(ids)

    given logger: Logger[IO] = LoggerFactory.getLoggerFromName(s"${index.value}.ingestor")
    val sleepDuration = collectDeletionInterval.getOrElse(1.hour)
    val now = until.getOrElse(Instant.now())
    registry(index).withRepo: repo =>
      val indexStream = repo
        .fetchUpdate(since.getOrElse(Instant.EPOCH), now)
        .evalTap(store_)
        .map(_.size)
        .compile
        .fold(0)(_ + _)
        .flatMap(total => logger.info(s"Reindexed $total documents for ${index.value}"))

      val writeDelete = IO.sleep(sleepDuration) *> StreamUtils
        .intervalStream(now.some, sleepDuration)
        .metered(sleepDuration)
        .flatMap(repo.fetchDelete)
        .flatMap(ids => fs2.Stream.emits(ids))
        .map(id => id.value + "\n")
        .through(fs2.text.utf8.encode[IO])
        .through(Files[IO].writeAll(fs2.io.file.Path(index.deletedAtLogPath)))
        .compile
        .drain
      logger.info(s"Starting reindexing for ${index.value} at ${now.toEpochMilli}") *>
        Supervisor
          .apply[IO](await = false)
          .use: supervisor =>
            supervisor.supervise(writeDelete) *> indexStream
        *> Files[IO]
          .readAll(fs2.io.file.Path(index.deletedAtLogPath))
          .through(fs2.text.utf8.decode[IO])
          .through(fs2.text.lines)
          .map(x => Id(x.trim))
          .chunkN(100)
          .evalMap(xs => delete_(xs.toList))
          .compile
          .drain
    *> logger.info(s"Reindexing for ${index.value} finished")

  private def putMappingsIfNotExists(elastic: ESClient[IO], index: Index)(using Logger[IO]): IO[Unit] =
    Handle
      .allow:
        elastic
          .indexExists(index)
          .ifM(Logger[IO].info(s"Index ${index.value} exists, start indexing"), elastic.putMapping(index))
      .rescue: e =>
        Logger[IO].error(e.asException)(s"Failed to check or put mapping for ${index.value}") *>
          e.asException.raiseError

  private def refreshIndexes(elastic: ESClient[IO], index: Index)(using Logger[IO]): IO[Unit] =
    Handle
      .allow:
        elastic
          .refreshIndex(index)
      .rescue: e =>
        Logger[IO].error(e.asException)(s"Failed to refresh index: ${index.value}") *>
          e.asException.raiseError
