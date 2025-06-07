package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
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
    List(forum.watch, ublog.watch, team.watch, study.watch, game.watch).parSequence_

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
        Ingestor(Index.Forum, forums, store, elastic, config.forum.startAt),
        Ingestor(Index.Ublog, ublogs, store, elastic, config.ublog.startAt),
        Ingestor(Index.Study, studies, store, elastic, config.study.startAt),
        Ingestor(Index.Game, games, store, elastic, config.game.startAt),
        Ingestor(Index.Team, teams, store, elastic, config.team.startAt)
      )
