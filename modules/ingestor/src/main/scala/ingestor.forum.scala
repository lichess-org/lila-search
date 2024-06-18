package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.mongodb.client.model.changestream.OperationType.*
import lila.search.spec.ForumSource
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.{ Aggregate, Filter, Projection }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

import java.time.Instant
import scala.concurrent.duration.*

trait ForumIngestor:
  def run(): fs2.Stream[IO, Unit]

object ForumIngestor:

  private val topicProjection = Projection.include(List("_id", "name"))

  private val interestedOperations = List(DELETE, INSERT, REPLACE).map(_.getValue)
  private val eventFilter          = Filter.in("operationType", interestedOperations)
  private val eventProjection = Projection.include(
    List(
      "clusterTime",
      "documentKey._id",
      "fullDocument.text",
      "fullDocument.topicId",
      "fullDocument.troll",
      "fullDocument.createdAt",
      "fullDocument.userId",
      "fullDocument.erasedAt"
    )
  )
  private val aggregate = Aggregate.matchBy(eventFilter).combinedWith(Aggregate.project(eventProjection))

  private val index = Index("forum")

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Forum)(
      using Logger[IO]
  ): IO[ForumIngestor] =
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Forum)(
      topics: MongoCollection,
      posts: MongoCollection
  )(using Logger[IO]): ForumIngestor = new:

    def run(): fs2.Stream[IO, Unit] =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting forum ingestor from $since"))
        .flatMap: last =>
          changes(last)
            .filterNot(_.isEmpty)
            .evalMap: events =>
              val lastEventTimestamp  = events.flatten(_.clusterTime.flatMap(_.asInstant)).maxOption
              val (toDelete, toIndex) = events.partition(_.isDelete)
              storeBulk(toIndex)
                *> deleteMany(toDelete)
                *> saveLastIndexedTimestamp(lastEventTimestamp.getOrElse(Instant.now()))

    private def storeBulk(events: List[ChangeStreamDocument[Document]]): IO[Unit] =
      info"Received ${events.size} forum posts to index" *>
        events.toSources
          .flatMap: sources =>
            elastic.storeBulk(index, sources) *> info"Indexed ${sources.size} forum posts"
          .handleErrorWith: e =>
            Logger[IO].error(e)(s"Failed to index forum posts: ${events.map(_.id).mkString(", ")}")

    private def deleteMany(events: List[ChangeStreamDocument[Document]]): IO[Unit] =
      elastic
        .deleteMany(index, events.flatMap(_.id.map(Id.apply)))
        .flatTap(_ => info"Deleted ${events.size} forum posts")
        .handleErrorWith: e =>
          Logger[IO].error(e)(s"Failed to delete forum posts: ${events.map(_.id).mkString(", ")}")

    private def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.name, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.name))(Instant.ofEpochSecond(_).some.pure[IO])

    // Fetches topic names by their ids
    private def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in("_id", ids))
        .projection(topicProjection)
        .all
        .map(_.map(doc => (doc.getString("_id") -> doc.getString("name")).mapN(_ -> _)).flatten.toMap)

    private def changes(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      val builder = posts.watch(aggregate)
      // skip the first event if we're starting from a specific timestamp
      // since the event at that timestamp is already indexed
      val skip = since.fold(0)(_ => 1)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .drop(skip)
        .groupWithin(config.batchSize, config.timeWindows.second)
        .evalTap(_.traverse_(x => debug"received $x"))
        .map(_.toList)

    extension (events: List[ChangeStreamDocument[Document]])
      private def toSources: IO[List[(String, ForumSource)]] =
        val topicIds = events.flatMap(_.topicId).distinct
        if topicIds.isEmpty then info"no topics found for posts: $events".as(Nil)
        else
          topicByIds(topicIds)
            .flatMap: topicMap =>
              events
                .traverse: event =>
                  (event.id, event.topicId, event.fullDocument)
                    .flatMapN: (id, topicId, doc) =>
                      doc.toSource(topicName = topicMap.get(topicId)).map(id -> _)
                    .match
                      case Some(value) => value.some.pure[IO]
                      case _           => info"failed to convert document to source: $event".as(none)
                .map(_.flatten)

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
        event.operationType == DELETE ||
          event.fullDocument.flatMap(_.get("erasedAt")).isDefined
