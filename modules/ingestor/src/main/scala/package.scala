package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sksamuel.elastic4s.Indexable
import mongo4cats.bson.Document
import mongo4cats.collection.GenericMongoCollection
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.Filter
import org.bson.BsonTimestamp
import org.typelevel.log4cats.Logger
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

given [A: Schema]: Indexable[A] = (a: A) => writeToString(a)

extension (instant: Instant)
  inline def asBsonTimestamp: BsonTimestamp = BsonTimestamp(instant.getEpochSecond.toInt, 1)

def range(field: String)(since: Instant, until: Option[Instant]): Filter =
  inline def gtes = Filter.gte(field, since)
  until.fold(gtes)(until => gtes.and(Filter.lt(field, until)))

def deleteMany(index: Index, ids: List[Id])(using Logger[IO])(using elastic: ESClient[IO]): IO[Unit] =
  elastic
    .deleteMany(index, ids)
    .flatTap(_ => Logger[IO].info(s"Deleted ${ids.size} ${index.value}s"))
    .handleErrorWith: e =>
      Logger[IO].error(e)(s"Failed to delete ${index.value}: ${ids.map(_.value).mkString(", ")}")
    .whenA(ids.nonEmpty)

def storeBulk[A](index: Index, sources: List[(String, A)])(using Schema[A], Logger[IO])(using
    elastic: ESClient[IO]
): IO[Unit] =
  Logger[IO].info(s"Received ${sources.size} docs to ${index.value}") *>
    elastic
      .storeBulk(index, sources)
      .handleErrorWith: e =>
        Logger[IO].error(e)(s"Failed to ${index.value} index: ${sources.map(_._1).mkString(", ")}")
      .whenA(sources.nonEmpty)
    *> Logger[IO].info(s"Indexed ${sources.size} ${index.value}s")

extension (s: String) def dollarPrefix = "$" + s

extension (store: KVStore)
  def saveLastIndexedTimestamp(index: Index, time: Option[Instant])(using Logger[IO]): IO[Unit] =
    val savedTime = time.getOrElse(Instant.now())
    store.put(index.value, savedTime)
      *> Logger[IO].info(s"Stored last indexed time ${savedTime.getEpochSecond} for $index")
