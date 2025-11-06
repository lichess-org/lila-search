package lila.search
package ingestor

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import lila.search.ingestor.opts.IndexOpts
import org.typelevel.log4cats.{ Logger, LoggerFactory }

object Indexer:

  type IndexOrAll = Index | Unit
  extension (index: IndexOrAll)
    inline def toList: List[Index] = index match
      case i: Index => List(i)
      case _ => Index.values.toList

  def apply(opts: IndexOpts, res: AppResources, config: AppConfig)(using LoggerFactory[IO]): IO[Unit] =
    given logger: Logger[IO] = LoggerFactory[IO].getLogger
    import opts.dry
    import res.*

    inline def go(index: Index) =
      import ElasticSink.given
      val runIndex = index match
        case Index.Forum =>
          ForumRepo(res.lichess, config.ingestor.forum).flatMap:
            run(index, _, store, elastic, opts)
        case Index.Ublog =>
          UblogRepo(res.lichess, config.ingestor.ublog).flatMap:
            run(index, _, store, elastic, opts)
        case Index.Study =>
          StudyRepo(res.study, res.studyLocal, config.ingestor.study).flatMap:
            run(index, _, store, elastic, opts)
        case Index.Game =>
          GameRepo(res.lichess, config.ingestor.game).flatMap:
            run(index, _, store, elastic, opts)
        case Index.Team =>
          TeamRepo(res.lichess, config.ingestor.team).flatMap:
            run(index, _, store, elastic, opts)

      putMappingsIfNotExists(res.elastic, index).whenA(!dry) *>
        runIndex.whenA(!dry) *>
        refreshIndexes(res.elastic, index).whenA(opts.refresh && !dry)

    opts.index.toList.traverse_(go)

  def run[A: Indexable](
      index: Index,
      repo: Repo[A],
      store: KVStore,
      elastic: ESClient[IO],
      opts: IndexOpts
  )(using LoggerFactory[IO]) =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")
    val stream =
      if opts.watch then repo.watch(opts.since.some)
      else repo.fetch(opts.since, opts.until)
    stream
      .through(sink(index, elastic, store))
      .compile
      .drain

  def sink[A: Indexable](
      index: Index,
      elastic: ESClient[IO],
      store: KVStore
  )(using logger: Logger[IO]): fs2.Pipe[IO, Repo.Result[A], Unit] =
    _.evalMap(ElasticSink.updateElastic(index, elastic, store))

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
