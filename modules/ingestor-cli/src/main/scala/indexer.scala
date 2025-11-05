package lila.search
package ingestor

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import lila.search.ingestor.opts.IndexOpts
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import Ingestor.given

// Index execution logic
object Indexer:

  def apply(opts: IndexOpts, res: AppResources, config: AppConfig)(using LoggerFactory[IO]): IO[Unit] =
    import opts.{ since, until, watch, dry }
    import res.*

    def go(index: Index) =
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

    opts.index.match
      case i: Index => go(i)
      case _ => Index.values.toList.traverse_(go)

  private def putMappingsIfNotExists(elastic: ESClient[IO], index: Index | Unit)(using LoggerFactory[IO]): IO[Unit] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    def go(index: Index) =
      Handle
        .allow:
          elastic
            .indexExists(index)
            .ifM(Logger[IO].info(s"Index ${index.value} exists, start indexing"), elastic.putMapping(index))
        .rescue: e =>
          Logger[IO].error(e.asException)(s"Failed to check or put mapping for ${index.value}") *>
            e.asException.raiseError
    index match
      case i: Index => go(i)
      case _ => Index.values.toList.traverse_(go)

  private def refreshIndexes(elastic: ESClient[IO], index: Index | Unit)(using LoggerFactory[IO]): IO[Unit] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    def go(index: Index) =
      Handle
        .allow:
          elastic
            .refreshIndex(index)
        .rescue: e =>
          Logger[IO].error(e.asException)(s"Failed to refresh index: ${index.value}") *>
            e.asException.raiseError
    index.match
      case i: Index => go(i)
      case _ => Index.values.toList.traverse_(go)
