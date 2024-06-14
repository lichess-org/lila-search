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

trait ForumWatch:
  def watch(since: Instant): fs2.Stream[IO, Seq[ForumSourceWithId]]

object ForumWatch:

  private val topicProjection = Projection.include(List("id", "name"))

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

  def apply(mongo: MongoDatabase[IO]): IO[ForumWatch] =
    (mongo.getCollection("f_post"), mongo.getCollection("f_topic")).mapN(apply)

  def apply(topics: MongoCollection, posts: MongoCollection): ForumWatch = new:
    def watch(since: Instant) =
      postStream.evalMap: events =>
        val topicIds = events.toList.flatMap(_.documentKey.flatMap(_.getString("_id"))).distinct
        topicByIds(topicIds).map: topicMap =>
          events.toList.flatten: event =>
            (event.id, event.fullDocument).flatMapN: (id, full) =>
              transform(topicMap.get(id), full).map(ForumSourceWithId.apply(id, _))

    def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in("id", ids))
        .projection(topicProjection)
        .all
        .map(_.map(doc => (doc.getString("id") -> doc.getString("name")).mapN(_ -> _)).flatten.toMap)

    def postStream: fs2.Stream[IO, Chunk[ChangeStreamDocument[Document]]] =
      posts
        .watch(aggregate)
        // .resumeAfter(resumeToken)
        .startAtOperationTime(startOperationalTime)
        .batchSize(batchSize)
        .boundedStream(batchSize)
        .chunkN(batchSize)

    def transform(topicName: Option[String], doc: Document): Option[ForumSource] =
      (
        doc.getString("text"),
        topicName,
        doc.getString("topicId"),
        doc.getBoolean("troll"),
        doc.getNested("createdAt").flatMap(_.asInstant).map(Timestamp.fromInstant),
        doc.getString("userId").some
      ).mapN(ForumSource.apply)
