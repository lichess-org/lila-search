package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType.*
import io.circe.*
import mongo4cats.bson.Document
import mongo4cats.circe.*
import mongo4cats.collection.MongoCollection
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
  ): IO[Repo[DbForum]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (mongo.getCollection("f_topic"), mongo.getCollectionWithCodec[DbPost]("f_post")).mapN(apply(config))

  def apply(config: IngestorConfig.Forum)(
      topics: MongoCollection[IO, Document],
      posts: MongoCollection[IO, DbPost]
  )(using Logger[IO]): Repo[DbForum] = new:

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
          .evalMap: posts =>
            val (toDelete, toIndex) = posts.partition(_.isErased)
            toIndex.toData
              .map: data =>
                Result(data, toDelete.map(p => Id(p.id)), Nil, None)

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[DbForum]] =
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
              Result(data, toDelete.flatten(using _.docId.map(Id.apply)), Nil, lastEventTimestamp)

    // Fetches topic names by their ids
    private def topicByIds(ids: Seq[String]): IO[Map[String, String]] =
      topics
        .find(Filter.in(_id, ids))
        .projection(Projection.include(List(_id, Topic.name)))
        .all
        .map(_.map(doc => (doc.id, doc.getString(Topic.name)).mapN(_ -> _)).flatten.toMap)

    extension (posts: List[DbPost])
      private def toData: IO[List[DbForum]] =
        val topicIds = posts.map(_.topicId).distinct
        topicIds.isEmpty.fold(
          info"no topics found for posts: $posts".as(Nil),
          topicByIds(topicIds)
            .flatMap: topicMap =>
              posts
                .traverse(_.toData(topicMap))
                .map(_.flatten)
        )

    extension (post: DbPost)

      private def toData(
          topicMap: Map[String, String]
      ): IO[Option[DbForum]] =
        topicMap
          .get(post.topicId)
          .map(DbForum(post, _))
          .pure[IO]
          .flatTap: data =>
            val reason =
              if topicMap.contains(post.topicId) then ""
              else s"topic name not found for topicId=${post.topicId}"
            info"failed to prepare forum data for post ${post.id}: $reason".whenA(data.isEmpty)

      private def isErased: Boolean =
        post.erasedAt.isDefined

      private def validText: Boolean =
        post.text.length <= config.maxPostLength

    extension (event: ChangeStreamDocument[DbPost])
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

case class DbPost(
    id: String, // _id
    text: String,
    topicId: String,
    troll: Boolean,
    createdAt: Instant,
    userId: String,
    erasedAt: Option[Instant]
)

object DbPost:
  import ForumRepo.F
  given Decoder[DbPost] =
    Decoder.forProduct7(_id, F.text, F.topicId, F.troll, F.createdAt, F.userId, F.erasedAt)(DbPost.apply)

  // We don't write to the database so we don't need to implement this
  given Encoder[DbPost] = new:
    def apply(a: DbPost): Json = ???

// DbForum combines a post with its topic name (fetched separately from f_topic collection)
case class DbForum(
    post: DbPost,
    topicName: String
):
  def id: String = post.id
  def topicId: String = post.topicId
  def isErased: Boolean = post.erasedAt.isDefined
