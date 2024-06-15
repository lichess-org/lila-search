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
import org.typelevel.log4cats.Logger
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

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore)(using
      Logger[IO]
  ): IO[ForumIngestor] =
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply(elastic, store))

  def apply(elastic: ESClient[IO], store: KVStore)(topics: MongoCollection, posts: MongoCollection)(using
      Logger[IO]
  ): ForumIngestor = new:

    def ingest(since: Option[Instant]): fs2.Stream[IO, Unit] =
      fs2.Stream
        .eval(store.get(index.name))
        .flatMap: last =>
          postStream(last)
            .evalMap: events =>
              val last = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
              events.toSources.map(_ -> last)
            .evalMap: (sources, last) =>
              elastic.storeBulk(index, sources) *> store.put(index.name, last.getOrElse(Instant.now()))

    // Fetches topic names by their ids
    def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in("_id", ids))
        .projection(topicProjection)
        .all
        .map(_.map(doc => (doc.getString("_id") -> doc.getString("name")).mapN(_ -> _)).flatten.toMap)

    def postStream(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      val builder = posts.watch(aggregate)
      since
        .fold(builder)(x => builder.startAtOperationTime(BsonTimestamp.apply(x.getEpochSecond().toInt, 1)))
        .batchSize(batchSize)
        .boundedStream(batchSize)
        .groupWithin(batchSize, 1.second)
        .map(_.toList)

    extension (events: List[ChangeStreamDocument[Document]])
      def toSources: IO[List[(String, ForumSource)]] =
        val topicIds = events.flatMap(_.topicId).distinct
        topicByIds(topicIds).map: topicMap =>
          events.flatten: event =>
            (event.id, event.topicId, event.fullDocument).flatMapN: (id, topicId, doc) =>
              doc.toForumSource(topicName = topicMap.get(topicId)).map(id -> _)

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

    extension (event: ChangeStreamDocument[Document])
      def topicId = event.fullDocument.flatMap(_.getString("topicId"))
