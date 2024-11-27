package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.LoggerFactory

trait Ingestor:
  def run(): IO[Unit]

object Ingestor:

  def apply(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      elastic: ESClient[IO],
      store: KVStore,
      config: IngestorConfig
  )(using LoggerFactory[IO]): IO[Ingestor] =
    (
      Forums(lichess, config.forum),
      Studies(study, local, config.study),
      Games(lichess, config.game),
      Teams(lichess, config.team)
    ).mapN: (forums, studies, games, teams) =>
      val forum = ForumIngestor(forums, elastic, store, config.forum)
      val study = StudyIngestor(studies, elastic, store, config.study)
      val game  = GameIngestor(games, elastic, store, config.game)
      val team  = TeamIngestor(teams, elastic, store, config.team)
      new Ingestor:
        def run() =
          fs2
            .Stream(forum.watch, team.watch, study.watch, game.watch)
            .covary[IO]
            .parJoinUnbounded
            .compile
            .drain
