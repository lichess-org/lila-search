package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.Logger

trait Ingestor:
  def run(): IO[Unit]

object Ingestor:

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore)(using Logger[IO]): IO[Ingestor] =
    ForumIngestor(mongo, elastic, store).map: f =>
      new Ingestor:
        def run(): IO[Unit] = f.ingest(none).compile.drain
