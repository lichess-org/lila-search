package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase

trait Ingestor:
  def run(): IO[Unit]

object Ingestor:
  def apply(db: MongoDatabase[IO]): Ingestor = new:

    def run(): IO[Unit] =
      db.listCollectionNames.flatMap: names =>
        names.toList.traverse_(name => IO.println(s"Collection: $name"))
