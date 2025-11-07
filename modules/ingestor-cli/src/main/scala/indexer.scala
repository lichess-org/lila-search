package lila.search
package ingestor

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
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

    (
      GameRepo(res.lichess, config.ingestor.game),
      ForumRepo(res.lichess, config.ingestor.forum),
      UblogRepo(res.lichess, config.ingestor.ublog),
      StudyRepo(res.study, res.studyLocal, config.ingestor.study),
      TeamRepo(res.lichess, config.ingestor.team)
    ).mapN(IndexRegistry.apply)
      .flatMap { case given IndexRegistry =>
        def go(index: Index) =
          val runIndex = run(index, store, elastic, opts)
          putMappingsIfNotExists(res.elastic, index).whenA(!dry) *>
            runIndex.whenA(!dry) *>
            refreshIndexes(res.elastic, index).whenA(opts.refresh && !dry)

        opts.index.toList.traverse_(go)
      }

  def run(
      index: Index,
      store: KVStore,
      elastic: ESClient[IO],
      opts: IndexOpts
  )(using registry: IndexRegistry, lf: LoggerFactory[IO]): IO[Unit] =
    given logger: Logger[IO] = lf.getLoggerFromName(s"${index.value}.ingestor")
    val im = registry(index)
    im.withRepo: repo =>
      val stream =
        if opts.watch then repo.watch(opts.since.some)
        else repo.fetch(opts.since, opts.until)
      stream
        .evalMap(ElasticSink.updateElastic(index, elastic, store))
        .compile
        .drain

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
