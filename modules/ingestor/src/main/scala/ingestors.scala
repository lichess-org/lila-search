package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.LoggerFactory

class Ingestors(
    val forum: Ingestor,
    val study: Ingestor,
    val game: Ingestor,
    val team: Ingestor
):
  def run(): IO[Unit] =
    List(forum.watch, team.watch, study.watch, game.watch).parSequence_

object Ingestors:

  def apply(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      store: KVStore,
      config: IngestorConfig
  )(using LoggerFactory[IO], ESClient[IO]): IO[Ingestors] =
    (
      ForumRepo(lichess, config.forum),
      StudyRepo(study, local, config.study),
      GameRepo(lichess, config.game),
      TeamRepo(lichess, config.team)
    ).mapN: (forums, studies, games, teams) =>
      new Ingestors(
        Ingestor(Index.Forum, forums, store, config.forum.startAt),
        Ingestor(Index.Study, studies, store, config.study.startAt),
        Ingestor(Index.Game, games, store, config.game.startAt),
        Ingestor(Index.Team, teams, store, config.team.startAt)
      )
