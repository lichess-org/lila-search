package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.LoggerFactory

class Ingestor(
    val forum: ForumIngestor,
    val study: StudyIngestor,
    val game: GameIngestor,
    val team: TeamIngestor
):
  def run(): IO[Unit] =
    List(forum.watch, team.watch, study.watch, game.watch).parSequence_

object Ingestor:

  def apply(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      store: KVStore,
      config: IngestorConfig
  )(using LoggerFactory[IO], ESClient[IO]): IO[Ingestor] =
    (
      Forums(lichess, config.forum),
      Studies(study, local, config.study),
      Games(lichess, config.game),
      Teams(lichess, config.team)
    ).mapN: (forums, studies, games, teams) =>
      new Ingestor(
        ForumIngestor(forums, store, config.forum),
        StudyIngestor(studies, store, config.study),
        GameIngestor(games, store, config.game),
        TeamIngestor(teams, store, config.team)
      )
