package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import ciris.ConfigDecoder
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sksamuel.elastic4s.Indexable
import lila.search.spec.Source
import mongo4cats.bson.Document
import mongo4cats.collection.GenericMongoCollection
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.Filter
import org.bson.BsonTimestamp
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*
import smithy4s.json.Json.given
import smithy4s.schema.Schema

import java.time.{DateTimeException, Instant}

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
given Indexable[Source] =
  _ match
    case f: Source.ForumCase => writeToString(f.forum)
    case g: Source.GameCase  => writeToString(g.game)
    case s: Source.StudyCase => writeToString(s.study)
    case t: Source.TeamCase  => writeToString(t.team)

extension (instant: Instant)
  inline def asBsonTimestamp: BsonTimestamp = BsonTimestamp(instant.getEpochSecond.toInt, 1)

def range(field: String)(since: Instant, until: Option[Instant]): Filter =
  inline def gtes = Filter.gte(field, since)
  until.fold(gtes)(until => gtes.and(Filter.lt(field, until)))

extension (elastic: ESClient[IO])

  def deleteMany_(index: Index, ids: List[Id])(using Logger[IO]): IO[Unit] =
    elastic
      .deleteMany(index, ids)
      .flatTap(_ => Logger[IO].info(s"Deleted ${ids.size} ${index.value}s"))
      .handleErrorWith: e =>
        Logger[IO].error(e)(s"Failed to delete ${index.value}: ${ids.map(_.value).mkString(", ")}")
      .whenA(ids.nonEmpty)

  @scala.annotation.targetName("deleteManyWithDocs")
  def deleteMany(index: Index, events: List[Document])(using Logger[IO]): IO[Unit] =
    info"Received ${events.size} ${index.value} to delete" *>
      deleteMany_(index, events.flatMap(_.id).map(Id.apply)).whenA(events.nonEmpty)

  @scala.annotation.targetName("deleteManyWithChanges")
  def deleteMany[A](index: Index, events: List[ChangeStreamDocument[A]])(using Logger[IO]): IO[Unit] =
    info"Received ${events.size} ${index.value} to delete" *>
      deleteMany_(index, events.flatMap(_.docId).map(Id.apply)).whenA(events.nonEmpty)

extension (s: String) def dollarPrefix = "$" + s

given ConfigDecoder[String, Instant] = ConfigDecoder[String]
  .as[Long]
  .mapOption("Instant"): l =>
    try Some(Instant.ofEpochSecond(l))
    catch case _: DateTimeException => None
