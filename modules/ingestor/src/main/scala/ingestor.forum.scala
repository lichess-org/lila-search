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
  // Utilize change events functionality of MongoDB to watch for changes in the forum posts collection.
  def watch(): fs2.Stream[IO, Unit]
  // Fetch posts from since to until and ingest to data
  def run(since: Instant, until: Option[Instant], dryRun: Boolean): fs2.Stream[IO, Unit]

object ForumIngestor:

  private val index = Index.Forum

  private val topicProjection = Projection.include(List("_id", "name"))

  private val interestedOperations = List(DELETE, INSERT, REPLACE).map(_.getValue)
  private val eventFilter          = Filter.in("operationType", interestedOperations)

  private val interestedFields = List("_id", "text", "topicId", "troll", "createdAt", "userId", "erasedAt")
  private val postProjection   = Projection.include(interestedFields)

  private val interestedEventFields =
    List("operationType", "clusterTime", "documentKey._id") ++ interestedFields.map("fullDocument." + _)
  private val eventProjection = Projection.include(interestedEventFields)

  private val aggregate = Aggregate.matchBy(eventFilter).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Forum)(
      using Logger[IO]
  ): IO[ForumIngestor] =
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Forum)(
      topics: MongoCollection,
      posts: MongoCollection
  )(using Logger[IO]): ForumIngestor = new:

    def watch(): fs2.Stream[IO, Unit] =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting forum ingestor from $since"))
        .flatMap: last =>
          changes(last)
            .evalMap: events =>
              val lastEventTimestamp  = events.flatten(_.clusterTime.flatMap(_.asInstant)).maxOption
              val (toDelete, toIndex) = events.partition(_.isDelete)
              storeBulk(toIndex.flatten(_.fullDocument))
                *> deleteMany(toDelete.flatten(_.fullDocument))
                *> saveLastIndexedTimestamp(lastEventTimestamp.getOrElse(Instant.now()))

    def run(since: Instant, until: Option[Instant], dryRun: Boolean): fs2.Stream[IO, Unit] =
      val filter = range("createdAt")(since, until).or(range("erasedAt")(since, until))
      posts
        .find(filter)
        .projection(postProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList)
        .metered(1.second) // to avoid overloading the elasticsearch
        .evalMap: docs =>
          val (toDelete, toIndex) = docs.partition(_.isErased)
          dryRun.fold(
            toIndex.traverse_(doc => debug"Would index $doc")
              *> toDelete.traverse_(doc => debug"Would delete $doc"),
            storeBulk(toIndex) *> deleteMany(toDelete)
          )

    private def storeBulk(events: List[Document]): IO[Unit] =
      info"Received ${events.size} forum posts to index" *>
        IO.whenA(events.nonEmpty):
          events.toSources
            .flatMap: sources =>
              elastic.storeBulk(index, sources) *> info"Indexed ${sources.size} forum posts"
            .handleErrorWith: e =>
              Logger[IO].error(e)(s"Failed to index forum posts: ${events.map(_._id).mkString(", ")}")

    private def deleteMany(events: List[Document]): IO[Unit] =
      info"Received ${events.size} forum posts to delete" *>
        IO.whenA(events.nonEmpty):
          deleteMany(events.flatMap(_._id).map(Id.apply))

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

    extension (events: List[Document])
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

      private def toSource(topicMap: Map[String, String]): IO[Option[SourceWithId]] =
        (doc._id, doc.getString("topicId"))
          .flatMapN: (id, topicId) =>
            doc.toSource(topicMap.get(topicId), topicId).map(id -> _)
          .match
            case Some(value) => value.some.pure[IO]
            case _ =>
              val reason = doc._id.fold("missing doc._id; ")(_ => "")
                + doc.topicId.fold("missing doc.topicId; ")(_ => "")
              info"failed to convert document to source: $doc because $reason".as(none)

      private def toSource(topicName: Option[String], topicId: String): Option[ForumSource] =
        (
          doc.getString("text").map(_.take(config.maxBodyLength)),
          topicName,
          topicId.some,
          doc.getBoolean("troll"),
          doc.getNested("createdAt").flatMap(_.asInstant).map(_.toEpochMilli()),
          doc.getString("userId").some
        ).mapN(ForumSource.apply)

      private def isErased: Boolean =
        doc.get("erasedAt").isDefined

      private def _id: Option[String] =
        doc.getString("_id")

      private def topicId: Option[String] =
        doc.getString("topicId")

    extension (event: ChangeStreamDocument[Document])
      private def isDelete: Boolean =
        event.operationType == DELETE || event.fullDocument.exists(_.isErased)
