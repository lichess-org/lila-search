package lila.search
package ingestor

import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sksamuel.elastic4s.Indexable
import mongo4cats.bson.Document
import mongo4cats.collection.GenericMongoCollection
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.Filter
import org.bson.BsonTimestamp
import smithy4s.json.Json.given
import smithy4s.schema.Schema

import java.time.Instant

val _id = "_id"

type MongoCollection = GenericMongoCollection[IO, Document, [A] =>> fs2.Stream[IO, A]]

given [A]: HasDocId[ChangeStreamDocument[A]] with
  extension (change: ChangeStreamDocument[A])
    def docId: Option[String] =
      change.documentKey.flatMap(_.id)

extension (doc: Document)
  private def id: Option[String] =
    doc.getString(_id)

extension (instant: Instant)
  inline def asBsonTimestamp: BsonTimestamp = BsonTimestamp(instant.getEpochSecond.toInt, 1)

def range(field: String)(since: Instant, until: Option[Instant]): Filter =
  inline def gtes = Filter.gte(field, since)
  until.fold(gtes)(until => gtes.and(Filter.lt(field, until)))

extension (s: String) def dollarPrefix = "$" + s

given [A: Schema]: Indexable[A] = (a: A) => writeToString(a)
