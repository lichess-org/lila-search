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
import org.typelevel.log4cats.syntax.*

import java.time.Instant
import scala.concurrent.duration.*

trait ForumIngestor:
  def ingest(since: Option[Instant]): fs2.Stream[IO, Unit]

object ForumIngestor:

  private val topicProjection = Projection.include(List("_id", "name"))

  val eventFilter = Filter.in("operationType", List("replace", "insert"))
  val aggregate   = Aggregate.matchBy(eventFilter)

  val startOperationalTime = BsonTimestamp(1717222680, 1) // the time that We lost forums indexer

  val index = Index("forum")

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Config)(
      using Logger[IO]
  ): IO[ForumIngestor] =
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Config)(
      topics: MongoCollection,
      posts: MongoCollection
  )(using Logger[IO]): ForumIngestor = new:

    def ingest(since: Option[Instant]): fs2.Stream[IO, Unit] =
      fs2.Stream
        .eval(store.get(index.name))
        .flatMap: last =>
          postStream(last)
            .evalMap: events =>
              val last = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
              events.toSources.map(_ -> last)
            .evalMap: (sources, last) =>
              elastic.storeBulk(index, sources)
                *> info"Indexed ${sources.size} forum posts"
                *> store.put(index.name, last.getOrElse(Instant.now()))
                *> info"Stored last indexed time $last for index ${index.name}"

    // Fetches topic names by their ids
    def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in("_id", ids))
        .projection(topicProjection)
        .all
        .map(_.map(doc => (doc.getString("_id") -> doc.getString("name")).mapN(_ -> _)).flatten.toMap)

    def postStream(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      posts
        .watch(aggregate)
        .startAtOperationTime(since.fold(startOperationalTime)(_.asBsonTimestamp))
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .groupWithin(config.batchSize, config.timeWindows.second)
        .map(_.toList)

    extension (events: List[ChangeStreamDocument[Document]])
      def toSources: IO[List[(String, ForumSource)]] =
        val topicIds = events.flatMap(_.topicId).distinct
        topicByIds(topicIds).map: topicMap =>
          events.flatten: event =>
            (event.id, event.topicId, event.fullDocument).flatMapN: (id, topicId, doc) =>
              doc.toSource(topicName = topicMap.get(topicId)).map(id -> _)

    extension (doc: Document)
      def toSource(topicName: Option[String]): Option[ForumSource] =
        (
          doc.getString("text").map(_.take(config.maxBodyLength)),
          topicName,
          doc.getString("topicId"),
          doc.getBoolean("troll"),
          doc.getNested("createdAt").flatMap(_.asInstant).map(_.toEpochMilli()),
          doc.getString("userId").some
        ).mapN(ForumSource.apply)

    extension (event: ChangeStreamDocument[Document])
      def topicId = event.fullDocument.flatMap(_.getString("topicId"))
