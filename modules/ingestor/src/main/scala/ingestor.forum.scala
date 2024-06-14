package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import lila.search.spec.ForumSource
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.{ Aggregate, Filter, Projection }
import org.bson.BsonTimestamp
import smithy4s.Timestamp

import java.time.Instant
import scala.concurrent.duration.*

trait ForumIngestor:
  def ingest(since: Option[Instant]): fs2.Stream[IO, Unit]

object ForumIngestor:

  private val topicProjection = Projection.include(List("_id", "name"))

  val eventFilter = Filter.in("operationType", List("replace", "insert"))
  val aggregate   = Aggregate.matchBy(eventFilter)

  val batchSize            = 100
  val startOperationalTime = BsonTimestamp(1718270441, 1)

  val index = Index("forum")

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO]): IO[ForumIngestor] =
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply(elastic))

  def apply(elastic: ESClient[IO])(topics: MongoCollection, posts: MongoCollection): ForumIngestor = new:

    def ingest(since: Option[Instant]): fs2.Stream[IO, Unit] =
      postStream
        .evalMap: events =>
          val topicIds = events.flatMap(_.fullDocument.flatMap(_.getString("topicId"))).distinct
          topicByIds(topicIds).map: topicMap =>
            events.flatten: event =>
              (event.id, event.fullDocument.flatMap(_.topicId), event.fullDocument).flatMapN:
                (id, topicId, full) => full.toForumSource(topicMap.get(topicId)).map(id -> _)
        .evalMap(elastic.storeBulk(index, _))

    def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in("_id", ids))
        .projection(topicProjection)
        .all
        .map(_.map(doc => (doc.getString("_id") -> doc.getString("name")).mapN(_ -> _)).flatten.toMap)

    def postStream: fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      posts
        .watch(aggregate)
        .startAtOperationTime(startOperationalTime)
        .batchSize(batchSize)
        .boundedStream(batchSize)
        .groupWithin(batchSize, 1.second)
        .map(_.toList)

    extension (doc: Document)
      def toForumSource(topicName: Option[String]): Option[ForumSource] =
        (
          doc.getString("text"),
          topicName,
          doc.getString("topicId"),
          doc.getBoolean("troll"),
          doc.getNested("createdAt").flatMap(_.asInstant).map(Timestamp.fromInstant),
          doc.getString("userId").some
        ).mapN(ForumSource.apply)

      def topicId = doc.getString("topicId")
