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
      "operationType",
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

  private val index = Index.Forum

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
            .evalMap: events =>
              val lastEventTimestamp  = events.flatten(_.clusterTime.flatMap(_.asInstant)).maxOption
              val (toDelete, toIndex) = events.partition(_.isDelete)
              storeBulk(toIndex)
                *> deleteMany(toDelete)
                *> saveLastIndexedTimestamp(lastEventTimestamp.getOrElse(Instant.now()))

    private def storeBulk(events: List[ChangeStreamDocument[Document]]): IO[Unit] =
      info"Received ${events.size} forum posts to index" *>
        IO.whenA(events.nonEmpty):
          events.toSources
            .flatMap: sources =>
              elastic.storeBulk(index, sources) *> info"Indexed ${sources.size} forum posts"
            .handleErrorWith: e =>
              Logger[IO].error(e)(s"Failed to index forum posts: ${events.map(_.id).mkString(", ")}")

    private def deleteMany(events: List[ChangeStreamDocument[Document]]): IO[Unit] =
      info"Received ${events.size} forum posts to delete" *>
        IO.whenA(events.nonEmpty):
          deleteMany(events.flatMap(_.id).map(Id.apply))

    @scala.annotation.targetName("deleteManyWithIds")
    private def deleteMany(ids: List[Id]): IO[Unit] =
      IO.whenA(ids.nonEmpty):
        elastic
          .deleteMany(index, ids)
          .flatTap(_ => info"Deleted ${ids.size} forum posts")
          .handleErrorWith: e =>
            Logger[IO].error(e)(s"Failed to delete forum posts: ${ids.map(_.value).mkString(", ")}")

    private def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(Instant.ofEpochSecond(_).some.pure[IO])

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

    private type SourceWithId = (String, ForumSource)
    extension (events: List[ChangeStreamDocument[Document]])
      private def toSources: IO[List[SourceWithId]] =
        val topicIds = events.flatMap(_.topicId).distinct
        topicIds.isEmpty.fold(
          info"no topics found for posts: $events".as(Nil),
          topicByIds(topicIds)
            .flatMap: topicMap =>
              events
                .traverse(_.toSource(topicMap))
                .map(_.flatten)
        )

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

      private def toSource(topicMap: Map[String, String]): IO[Option[SourceWithId]] =
        (event.id, event.topicId, event.fullDocument)
          .flatMapN: (id, topicId, doc) =>
            doc.toSource(topicName = topicMap.get(topicId)).map(id -> _)
          .match
            case Some(value) => value.some.pure[IO]
            case _ =>
              val reason = event.id.fold("missing event.id; ")(_ => "")
                + event.topicId.fold("missing event.topicId; ")(_ => "")
                + event.fullDocument.fold("missing event.fullDocument; ")(_ => "")
              info"failed to convert document to source: $event because $reason".as(none)
