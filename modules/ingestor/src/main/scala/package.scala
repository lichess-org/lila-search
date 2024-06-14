package lila.search
package ingestor
import cats.effect.IO
import mongo4cats.bson.Document
import mongo4cats.collection.GenericMongoCollection
import mongo4cats.models.collection.ChangeStreamDocument

type MongoCollection = GenericMongoCollection[IO, Document, [A] =>> fs2.Stream[IO, A]]

extension [A](change: ChangeStreamDocument[A])
  def id: Option[String] = change.documentKey.flatMap(_.getString("_id"))
