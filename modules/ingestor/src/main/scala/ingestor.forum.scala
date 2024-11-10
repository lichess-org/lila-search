package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType.*
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.{ Aggregate, Filter, Projection }
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant
import scala.concurrent.duration.*

trait ForumIngestor:
  // watch change events from MongoDB and ingest forum posts into elastic search
  def watch: fs2.Stream[IO, Unit]
  // Fetch posts in [since, until] and ingest into elastic search
  def run(since: Instant, until: Instant, dryRun: Boolean): fs2.Stream[IO, Unit]

object ForumIngestor:

  private val index = Index.Forum

  private val interestedOperations = List(DELETE, INSERT, REPLACE, UPDATE).map(_.getValue)

  private def maxPostSizeFilter(max: Int) =
    Filter.expr(s"{ $$lte: [{ $$strLenCP: '$$fullDocument.text' }, $max] }")

  private def eventFilter(maxPostLength: Int) =
    Filter.in("operationType", interestedOperations) && maxPostSizeFilter(maxPostLength)

  private val interestedFields = List(_id, F.text, F.topicId, F.troll, F.createdAt, F.userId, F.erasedAt)
  private val postProjection   = Projection.include(interestedFields)

  private val interestedEventFields =
    List("operationType", "clusterTime", "documentKey._id") ++ interestedFields.map("fullDocument." + _)
  private val eventProjection = Projection.include(interestedEventFields)

  private def aggregate(maxPostLength: Int) =
    Aggregate.matchBy(eventFilter(maxPostLength)).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Forum)(
      using LoggerFactory[IO]
  ): IO[ForumIngestor] =
    given Logger[IO] = summon[LoggerFactory[IO]].getLogger
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Forum)(
      topics: MongoCollection,
      posts: MongoCollection
  )(using Logger[IO]): ForumIngestor = new:

    def watch: fs2.Stream[IO, Unit] =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting forum ingestor from $since"))
        .flatMap: last =>
          changes(last)
            .evalMap: events =>
              val lastEventTimestamp  = events.flatten(_.clusterTime.flatMap(_.asInstant)).maxOption
              val (toDelete, toIndex) = events.partition(_.isDelete)
              storeBulk(toIndex.flatten(_.fullDocument))
                *> elastic.deleteMany(index, toDelete)
                *> saveLastIndexedTimestamp(lastEventTimestamp.getOrElse(Instant.now()))

    def run(since: Instant, until: Instant, dryRun: Boolean): fs2.Stream[IO, Unit] =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
        .or(range(F.erasedAt)(since, until.some))
      posts
        .find(filter)
        .projection(postProjection)
        .boundedStream(config.batchSize)
        .filter(_.validText)
        .chunkN(config.batchSize)
        .map(_.toList)
        .metered(1.second) // to avoid overloading the elasticsearch
        .evalMap: docs =>
          val (toDelete, toIndex) = docs.partition(_.isErased)
          dryRun.fold(
            toIndex.traverse_(doc => debug"Would index $doc")
              *> toDelete.traverse_(doc => debug"Would delete $doc"),
            storeBulk(toIndex) *> elastic.deleteMany(index, toDelete)
          )

    private def storeBulk(docs: List[Document]): IO[Unit] =
      info"Received ${docs.size} forum posts to index" *>
        docs.toSources
          .flatMap: sources =>
            elastic.storeBulk(index, sources) *> info"Indexed ${sources.size} forum posts"
          .handleErrorWith: e =>
            Logger[IO].error(e)(s"Failed to index forum posts: ${docs.map(_.id).mkString(", ")}")
          .whenA(docs.nonEmpty)

    private def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(_.some.pure[IO])

    // Fetches topic names by their ids
    private def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in(_id, ids))
        .projection(Projection.include(List(_id, Topic.name)))
        .all
        .map(_.map(doc => (doc.id, doc.getString(Topic.name)).mapN(_ -> _)).flatten.toMap)

    private def changes(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      val builder = posts.watch(aggregate(config.maxPostLength))
      // skip the first event if we're starting from a specific timestamp
      // since the event at that timestamp is already indexed
      val skip = since.fold(0)(_ => 1)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .fullDocument(FullDocument.UPDATE_LOOKUP) // this is required for update event
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .drop(skip)
        .groupWithin(config.batchSize, config.timeWindows.second)
        .evalTap(_.traverse_(x => debug"received $x"))
        .map(_.toList.distincByDocId)

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
        (doc.id, doc.topicId)
          .flatMapN: (id, topicId) =>
            doc.toSource(topicMap.get(topicId), topicId).map(id -> _)
          .pure[IO]
          .flatTap: source =>
            def reason = doc.id.fold("missing doc._id; ")(_ => "")
              + doc.topicId.fold("missing doc.topicId; ")(_ => "")
              + doc.topicId
                .map(id => topicMap.get(id).fold("topic or topic.name is missing")(_ => ""))
                .getOrElse("")
            info"failed to convert document to source: $doc because $reason".whenA(source.isEmpty)

      private def toSource(topicName: Option[String], topicId: String): Option[ForumSource] =
        (
          doc.getString(F.text),
          topicName,
          doc.getBoolean(F.troll),
          doc.getNested(F.createdAt).flatMap(_.asInstant).map(_.toEpochMilli),
          doc.getString(F.userId).some
        ).mapN(ForumSource.apply(_, _, topicId, _, _, _))

      private def isErased: Boolean =
        doc.get("erasedAt").isDefined

      private def topicId: Option[String] =
        doc.getString(F.topicId)

      private def validText: Boolean =
        doc.getString(F.text).exists(_.length <= config.maxPostLength)

    extension (event: ChangeStreamDocument[Document])
      private def isDelete: Boolean =
        event.operationType == DELETE || event.fullDocument.exists(_.isErased)

  object F:
    val text      = "text"
    val topicId   = "topicId"
    val troll     = "troll"
    val userId    = "userId"
    val createdAt = "createdAt"
    val updatedAt = "updatedAt"
    val erasedAt  = "erasedAt"

  object Topic:
    val name = "name"
