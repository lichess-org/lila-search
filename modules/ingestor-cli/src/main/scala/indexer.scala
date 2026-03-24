package lila.search
package ingestor

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import lila.search.ingestor.game.ESGameIngestor
import lila.search.ingestor.opts.IndexOpts
import org.typelevel.log4cats.{ Logger, LoggerFactory }

object Indexer:

  type IndexOrAll = Index | Unit
  extension (index: IndexOrAll)
    inline def toList: List[Index] = index match
      case i: Index => List(i)
      case _ => Index.values.toList

class Indexer(val res: AppResources, val config: AppConfig)(using LoggerFactory[IO]):
  import Indexer.*

  given logger: Logger[IO] = LoggerFactory[IO].getLogger
  given KVStore = res.store
  given ESClient[IO] = res.elastic

  def index(opts: IndexOpts): IO[Unit] =

    def go(index: Index) = index match
      case Index.Game =>
        putMappingsIfNotExists(res.elastic, index).whenA(!opts.dry) *>
          GameRepo(res.lichess, config.ingestor.game).flatMap: repo =>
            val ingestor: Ingestor[DbGame] =
              if opts.dry then DryRunIngestor(index)
              else ESGameIngestor(res.elastic, res.botCache)
            val stream =
              if opts.watch then fs2.Stream.eval(opts.since.some.pure[IO]).flatMap(repo.watch)
              else repo.fetchAll(opts.since, opts.until)
            ingestor.ingest(stream) *>
              refreshIndexes(res.elastic, index).whenA(opts.refresh && !opts.dry)
      case _ =>
        putMappingsIfNotExists(res.elastic, index).whenA(!opts.dry) *>
          runIndex(index, opts) *>
          refreshIndexes(res.elastic, index).whenA(opts.refresh && !opts.dry)

    opts.index.toList.traverse_(go)

  private def runES[A: Indexable: HasStringId](
      index: Index,
      repo: IO[Repo[A]],
      opts: IndexOpts
  ): IO[Unit] =
    repo.flatMap: r =>
      val ingestor: Ingestor[A] =
        if opts.dry then DryRunIngestor(index)
        else ESIngestor(index, res.elastic)
      val stream =
        if opts.watch then r.watch(opts.since.some)
        else r.fetchAll(opts.since, opts.until)
      ingestor.ingest(stream)

  def runIndex(index: Index, opts: IndexOpts): IO[Unit] = index match
    case Index.Forum => runES(index, ForumRepo(res.lichess, config.ingestor.forum), opts)
    case Index.Ublog => runES(index, UblogRepo(res.lichess, config.ingestor.ublog), opts)
    case Index.Study => runES(index, StudyRepo(res.study, res.studyLocal, config.ingestor.study), opts)
    case Index.Team => runES(index, TeamRepo(res.lichess, config.ingestor.team), opts)
    case Index.Game => throw UnsupportedOperationException("Game indexing is handled separately")

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
