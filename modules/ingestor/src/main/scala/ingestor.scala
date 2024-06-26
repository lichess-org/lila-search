package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.Logger

trait Ingestor:
  def run(): IO[Unit]

object Ingestor:

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig)(using
      Logger[IO]
  ): IO[Ingestor] =
    (
      ForumIngestor(mongo, elastic, store, config.forum),
      TeamIngestor(mongo, elastic, store, config.team)
    )
      .mapN: (forum, team) =>
        new Ingestor:
          def run() =
            forum.watch.merge(team.watch).compile.drain
