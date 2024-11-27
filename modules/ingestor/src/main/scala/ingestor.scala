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
      TeamIngestor(lichess, elastic, store, config.team),
      StudyIngestor(study, local, elastic, store, config.study),
      GameIngestor(lichess, elastic, store, config.game)
    ).mapN: (forums, team, study, game) =>
      val forum = ForumIngestor(elastic, store, config.forum, forums)
      new Ingestor:
        def run() =
          fs2
            .Stream(forum.watch, team.watch, study.watch, game.watch)
            .covary[IO]
            .parJoinUnbounded
            .compile
            .drain
