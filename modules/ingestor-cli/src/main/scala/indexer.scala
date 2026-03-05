package lila.search
package ingestor

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import lila.search.ingestor.opts.{ IndexOpts, ReindexOpts }
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import lila.search.ingestor.game.GameCHIngestor 

import java.time.Instant

object Indexer:

  type IndexOrAll = Index | Unit
  extension (index: IndexOrAll)
    inline def toList: List[Index] = index match
      case i: Index => List(i)
      case _ => Index.values.toList

class Indexer(val res: AppResources, val config: AppConfig)(using LoggerFactory[IO]):
  import Indexer.*

  given logger: Logger[IO] = LoggerFactory[IO].getLogger
  given registry: IndexRegistry = IndexRegistry(
    GameRepo(res.lichess, config.ingestor.game),
    ForumRepo(res.lichess, config.ingestor.forum),
    UblogRepo(res.lichess, config.ingestor.ublog),
    StudyRepo(res.study, res.studyLocal, config.ingestor.study),
    TeamRepo(res.lichess, config.ingestor.team)
  )
  given KVStore = res.store
  given ESClient[IO] = res.elastic

  def index(opts: IndexOpts): IO[Unit] =

    def go(index: Index) = index match
      case Index.Game =>
        res.clickhouse.createTable.whenA(!opts.dry) *>
          GameRepo(res.lichess, config.ingestor.game).flatMap: repo =>
            if opts.watch then GameCHIngestor.watch(index, repo, res.clickhouse, opts.since.some, opts.dry)
            else GameCHIngestor.fetchAll(index, repo, res.clickhouse, opts.since, opts.until, opts.dry)
      case _ =>
        putMappingsIfNotExists(res.elastic, index).whenA(!opts.dry) *>
          runIndex(index, opts) *>
          refreshIndexes(res.elastic, index).whenA(opts.refresh && !opts.dry)

    opts.index.toList.traverse_(go)

  def reindex(opts: ReindexOpts) =
    def go(index: Index) =
      putMappingsIfNotExists(res.elastic, index).whenA(!opts.dry) *>
        runReindex(index, opts) *>
        refreshIndexes(res.elastic, index).whenA(!opts.dry)
    if opts.index != Index.Study then
      logger.warn(
        s"Reindexing is only supported for the Study index. No action taken for ${opts.index.toString}."
      )
    else opts.index.toList.traverse_(go)

  def runIndex(other: Index, opts: IndexOpts): IO[Unit] =
    given logger: Logger[IO] = LoggerFactory.getLoggerFromName(s"${other.value}.ingestor")
    val im = registry(other)
    im.withRepo: repo =>
      val stream =
        if opts.watch then repo.watch(opts.since.some)
        else repo.fetchAll(opts.since, opts.until)
      val f: Repo.Result[im.Out] => IO[Unit] =
        if opts.dry then
          result =>
            result.toIndex.traverse_(item => Logger[IO].info(s"Dry run - would index ${item.id}")) *>
              result.toDelete.traverse_(id => Logger[IO].info(s"Dry run - would delete ${id.value}"))
        else other.updateElastic

      stream
        .evalMap(f)
        .compile
        .drain

  def runReindex(
      index: Index,
      opts: ReindexOpts
  ): IO[Unit] =
    import opts.{ since, until, dry }

    def store_[A: Indexable: HasStringId](sources: List[A]): IO[Unit] =
      if dry then
        Logger[IO].info(s"Dry run - would index ${sources.size} docs to ${index.value}")
          *> sources.traverse_(item =>
            Logger[IO].debug(s"Dry run - would index ${summon[Indexable[A]].json(item)}")
          )
      else index.storeBulk(sources)

    given logger: Logger[IO] = LoggerFactory.getLoggerFromName(s"${index.value}.ingestor")
    val now = until.getOrElse(Instant.now())
    registry(index).withRepo: repo =>
      repo
        .fetchUpdate(since.getOrElse(Instant.EPOCH), now)
        .evalTap(store_)
        .map(_.size)
        .compile
        .fold(0)(_ + _)
        .flatMap(total => logger.info(s"Reindexed $total documents for ${index.value}"))

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
