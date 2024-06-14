package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import fs2.Chunk
import lila.search.spec.{ ForumSource, ForumSourceWithId }
import mongo4cats.bson.{ BsonValue, Document }
import mongo4cats.database.MongoDatabase
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.{ Aggregate, Filter, Projection }
import org.bson.{ BsonDocument, BsonTimestamp }
import smithy4s.Timestamp

import java.time.Instant
import scala.concurrent.duration.*

trait ForumIngestor:
  def watch(since: Option[Instant]): fs2.Stream[IO, Seq[ForumSourceWithId]]

object ForumIngestor:

  private val topicProjection = Projection.include(List("_id", "name"))

  val eventFilter = Filter.in("operationType", List("replace", "insert"))
  val aggregate   = Aggregate.matchBy(eventFilter)
  val resumeToken = BsonDocument(
    "_data",
    BsonValue
      .string(
        "82666AB9B1000000012B022C0100296E5A1004AC00875A70284DF9B1268E0E8EC36E5E463C5F6964003C6D446144686A6D38000004"
      )
      .asJava
  )

  val batchSize            = 100
  val startOperationalTime = BsonTimestamp(1718270441, 1)

  def apply(mongo: MongoDatabase[IO]): IO[ForumIngestor] =
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply)

  def apply(topics: MongoCollection, posts: MongoCollection): ForumIngestor = new:
    def watch(since: Option[Instant]) =
      postStream.evalMap: events =>
        val topicIds = events.toList.flatMap(_.fullDocument.flatMap(_.getString("topicId"))).distinct
        topicByIds(topicIds).map: topicMap =>
          events.toList.flatten: event =>
            (event.fullDocument.flatMap(_.topicId), event.fullDocument).flatMapN: (topicId, full) =>
              transform(topicMap.get(topicId), full).map(ForumSourceWithId.apply(topicId, _))

    def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in("_id", ids))
        .projection(topicProjection)
        .all
        .map(_.map(doc => (doc.getString("_id") -> doc.getString("name")).mapN(_ -> _)).flatten.toMap)

    def postStream: fs2.Stream[IO, Chunk[ChangeStreamDocument[Document]]] =
      posts
        .watch(aggregate)
        // .resumeAfter(resumeToken)
        .startAtOperationTime(startOperationalTime)
        .batchSize(batchSize)
        .boundedStream(batchSize)
        // .evalTap(IO.println)
        .groupWithin(batchSize, 1.second)
      // .evalTap(IO.println)

    def transform(topicName: Option[String], doc: Document): Option[ForumSource] =
      (
        doc.getString("text"),
        topicName,
        doc.getString("topicId"),
        doc.getBoolean("troll"),
        doc.getNested("createdAt").flatMap(_.asInstant).map(Timestamp.fromInstant),
        doc.getString("userId").some
      ).mapN(ForumSource.apply)

    def transformpp(topicName: Option[String], doc: Document): Option[ForumSource] =
      (
        doc.getString("text"),
        topicName,
        doc.getString("topicId"),
        doc.getBoolean("troll"),
        doc.getNested("createdAt").flatMap(_.asInstant).map(Timestamp.fromInstant),
        doc.getString("userId").some
      ).mapN(ForumSource.apply)

    extension (doc: Document) def topicId = doc.getString("topicId")
