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

import Repo.{ *, given }

object ForumRepo:

  private val interestedOperations = List(DELETE, INSERT, REPLACE, UPDATE).map(_.getValue)

  private def maxPostSizeFilter(max: Int) =
    Filter.expr(s"{ $$lte: [{ $$strLenCP: '$$fullDocument.text' }, $max] }")

  private def eventFilter(maxPostLength: Int) =
    Filter.in("operationType", interestedOperations) && maxPostSizeFilter(maxPostLength)

  private val interestedFields = List(_id, F.text, F.topicId, F.troll, F.createdAt, F.userId, F.erasedAt)
  private val postProjection = Projection.include(interestedFields)

  private val interestedEventFields =
    List("operationType", "clusterTime", "documentKey._id") ++ interestedFields.map("fullDocument." + _)
  private val eventProjection = Projection.include(interestedEventFields)

  private def aggregate(maxPostLength: Int) =
    Aggregate.matchBy(eventFilter(maxPostLength)).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], config: IngestorConfig.Forum)(using
      LoggerFactory[IO]
  ): IO[Repo[(Document, String, String)]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (mongo.getCollection("f_topic"), mongo.getCollection("f_post")).mapN(apply(config))

  def apply(config: IngestorConfig.Forum)(
      topics: MongoCollection,
      posts: MongoCollection
  )(using Logger[IO]): Repo[(Document, String, String)] = new:

    def fetch(since: Instant, until: Instant) =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
        .or(range(F.erasedAt)(since, until.some))
      fs2.Stream.eval(info"Fetching forum posts from $since to $until") *>
        posts
          .find(filter)
          .projection(postProjection)
          .boundedStream(config.batchSize)
          .filter(_.validText)
          .chunkN(config.batchSize)
          .map(_.toList)
          .metered(1.second)
          .evalMap: events =>
            val (toDelete, toIndex) = events.partition(_.isErased)
            toIndex.toData
              .map: data =>
                Result(data, toDelete.flatten(using _.id.map(Id.apply)), none)

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[(Document, String, String)]] =
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
        .evalMap: events =>
          val lastEventTimestamp = events.flatten(using _.clusterTime.flatMap(_.asInstant)).maxOption
          val (toDelete, toIndex) = events.partition(_.isDelete)
          toIndex
            .flatten(using _.fullDocument)
            .toData
            .map: data =>
              Result(data, toDelete.flatten(using _.docId.map(Id.apply)), lastEventTimestamp)

    // Fetches topic names by their ids
    private def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in(_id, ids))
        .projection(Projection.include(List(_id, Topic.name)))
        .all
        .map(_.map(doc => (doc.id, doc.getString(Topic.name)).mapN(_ -> _)).flatten.toMap)

    extension (events: List[Document])
      private def toData: IO[List[SourceWithId[(Document, String, String)]]] =
        val topicIds = events.flatMap(_.topicId).distinct
        topicIds.isEmpty.fold(
          info"no topics found for posts: $events".as(Nil),
          topicByIds(topicIds)
            .flatMap: topicMap =>
              events
                .traverse(_.toData(topicMap))
                .map(_.flatten)
        )

    extension (doc: Document)

      private def toData(
          topicMap: Map[String, String]
      ): IO[Option[SourceWithId[(Document, String, String)]]] =
        (doc.id, doc.topicId)
          .flatMapN: (id, topicId) =>
            topicMap.get(topicId).map(topicName => id -> (doc, topicId, topicName))
          .pure[IO]
          .flatTap: data =>
            def reason = doc.id.fold("missing doc._id; ")(_ => "")
              + doc.topicId.fold("missing doc.topicId; ")(_ => "")
              + doc.topicId
                .map(id => topicMap.get(id).fold("topic or topic.name is missing")(_ => ""))
                .getOrElse("")
            info"failed to prepare document data: $doc because $reason".whenA(data.isEmpty)

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
    val text = "text"
    val topicId = "topicId"
    val troll = "troll"
    val userId = "userId"
    val createdAt = "createdAt"
    val updatedAt = "updatedAt"
    val erasedAt = "erasedAt"

  object Topic:
    val name = "name"
