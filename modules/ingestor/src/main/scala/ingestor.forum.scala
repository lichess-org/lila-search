package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.mongodb.client.model.changestream.OperationType
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
  def run(): fs2.Stream[IO, Unit]

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

    def run(): fs2.Stream[IO, Unit] =
      fs2.Stream
        .eval(store.get(index.name).flatMap(since => info"Starting forum ingestor from $since".as(since)))
        .flatMap: last =>
          postStream(last)
            .evalMap: events =>
              val last                = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
              val (toDelete, toIndex) = events.partition(_.isDelete)
              toIndex.toSources.flatMap(elastic.storeBulk(index, _))
                *> info"Indexed ${toIndex.size} forum posts"
                *> elastic.deleteMany(index, toDelete.flatMap(x => x.id.map(Id.apply)))
                *> info"Deleted ${toDelete.size} forum posts"
                *> store.put(index.name, last.getOrElse(Instant.now()))
                *> info"Stored last indexed time $last for index ${index.name}"

    // Fetches topic names by their ids
    private def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in("_id", ids))
        .projection(topicProjection)
        .all
        .map(_.map(doc => (doc.getString("_id") -> doc.getString("name")).mapN(_ -> _)).flatten.toMap)

    private def postStream(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      posts
        .watch(aggregate)
        .startAtOperationTime(since.fold(startOperationalTime)(_.asBsonTimestamp))
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .groupWithin(config.batchSize, config.timeWindows.second)
        .map(_.toList)

    extension (events: List[ChangeStreamDocument[Document]])
      private def toSources: IO[List[(String, ForumSource)]] =
        val topicIds = events.flatMap(_.topicId).distinct
        topicByIds(topicIds).map: topicMap =>
          events.flatten: event =>
            (event.id, event.topicId, event.fullDocument).flatMapN: (id, topicId, doc) =>
              doc.toSource(topicName = topicMap.get(topicId)).map(id -> _)

    extension (doc: Document)
      private def toSource(topicName: Option[String]): Option[ForumSource] =
        (
          doc.getString("text").map(_.take(config.maxBodyLength)),
          topicName,
          doc.getString("topicId"),
          doc.getBoolean("troll"),
          doc.getNested("createdAt").flatMap(_.asInstant).map(_.toEpochMilli()),
          doc.getString("userId").some
        ).mapN(ForumSource.apply)

    extension (event: ChangeStreamDocument[Document])
      private def topicId = event.fullDocument.flatMap(_.getString("topicId"))

      private def isDelete: Boolean =
        event.operationType == OperationType.DELETE ||
          event.fullDocument.flatMap(_.get("erasedAt")).isDefined
