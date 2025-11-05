package lila.search
package ingestor

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import lila.search.ingestor.opts.IndexOpts
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import Ingestor.given

object Indexer:

  type IndexOrAll = Index | Unit
  extension (index: IndexOrAll)
    inline def toList: List[Index] = index match
      case i: Index => List(i)
      case _ => Index.values.toList

  def apply(opts: IndexOpts, res: AppResources, config: AppConfig)(using LoggerFactory[IO]): IO[Unit] =
    given logger: Logger[IO] = LoggerFactory[IO].getLogger
    import opts.{ since, until, watch, dry }
    import res.*

    inline def go(index: Index) =
      val ingestor = index match
        case Index.Forum =>
          ForumRepo(res.lichess, config.ingestor.forum).map:
            Ingestor.index(index, _, store, elastic, since, until, watch, dry)
        case Index.Ublog =>
          UblogRepo(res.lichess, config.ingestor.ublog).map:
            Ingestor.index(index, _, store, elastic, since, until, watch, dry)
        case Index.Study =>
          StudyRepo(res.study, res.studyLocal, config.ingestor.study).map:
            Ingestor.index(index, _, store, elastic, since, until, watch, dry)
        case Index.Game =>
          GameRepo(res.lichess, config.ingestor.game).map:
            Ingestor.index(index, _, store, elastic, since, until, watch, dry)
        case Index.Team =>
          TeamRepo(res.lichess, config.ingestor.team).map:
            Ingestor.index(index, _, store, elastic, since, until, watch, dry)

      putMappingsIfNotExists(res.elastic, index) *>
        ingestor.flatMap(_.run()) *>
        refreshIndexes(res.elastic, index).whenA(opts.refresh)

    opts.index.toList.traverse_(go)

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
