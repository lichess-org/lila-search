package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.LoggerFactory

class Ingestors(
    val forum: Ingestor,
    val ublog: Ingestor,
    val study: Ingestor,
    val game: Ingestor,
    val team: Ingestor
):
  def run(): IO[Unit] =
    List(forum.run(), ublog.run(), team.run(), study.run(), game.run()).parSequence_

object Ingestors:

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
        Ingestor.watchPartial(
          Index.Forum,
          forums,
          Translate.forum.tupled,
          store,
          elastic,
          config.forum.startAt
        ),
        Ingestor.watchPartial(Index.Ublog, ublogs, Translate.ublog, store, elastic, config.ublog.startAt),
        Ingestor.watchPartial(
          Index.Study,
          studies,
          Translate.study.tupled,
          store,
          elastic,
          config.study.startAt
        ),
        Ingestor.watch(Index.Game, games, Translate.game, store, elastic, config.game.startAt),
        Ingestor.watch(Index.Team, teams, Translate.team, store, elastic, config.team.startAt)
      )
