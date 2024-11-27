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
      given ESClient[IO] = elastic
      val forum          = ForumIngestor(forums, store, config.forum)
      val study          = StudyIngestor(studies, store, config.study)
      val game           = GameIngestor(games, store, config.game)
      val team           = TeamIngestor(teams, store, config.team)
      new Ingestor:
        def run(): IO[Unit] =
          List(forum.watch, team.watch, study.watch, game.watch).parSequence_
