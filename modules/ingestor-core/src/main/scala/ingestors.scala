package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.LoggerFactory

trait IndexIngestor:
  def ingestAll(): IO[Unit]
  def ingest(index: Index): IO[Unit]

class Ingestors(
    val forum: Ingestor,
    val ublog: Ingestor,
    val study: Ingestor,
    val game: Ingestor,
    val team: Ingestor
):
  def run(): IO[Unit] =
    List(forum.run(), ublog.run(), team.run(), study.run(), game.run()).parSequence_

  def get(index: Index): Ingestor =
    index match
      case Index.Forum => forum
      case Index.Ublog => ublog
      case Index.Study => study
      case Index.Game => game
      case Index.Team => team

object Ingestors:

  import Ingestor.given

  def apply(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      store: KVStore,
      elastic: ESClient[IO],
      config: IngestorConfig
  )(using LoggerFactory[IO]): IO[Ingestors] =
    (
      ForumRepo(lichess, config.forum),
      UblogRepo(lichess, config.ublog),
      StudyRepo(study, local, config.study),
      GameRepo(lichess, config.game),
      TeamRepo(lichess, config.team)
    ).mapN: (forums, ublogs, studies, games, teams) =>
      new Ingestors(
        Ingestor.watch(Index.Forum, forums, store, elastic, config.forum.startAt),
        Ingestor.watch(Index.Ublog, ublogs, store, elastic, config.ublog.startAt),
        Ingestor.watch(Index.Study, studies, store, elastic, config.study.startAt),
        Ingestor.watch(Index.Game, games, store, elastic, config.game.startAt),
        Ingestor.watch(Index.Team, teams, store, elastic, config.team.startAt)
      )
