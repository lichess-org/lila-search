package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType.*
import io.circe.*
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

object UblogRepo:

  private val interestedOperations = List(DELETE, INSERT, REPLACE, UPDATE).map(_.getValue)

  private val interestedFields =
    List(
      _id,
      F.markdown,
      F.title,
      F.intro,
      F.topics,
      F.blog,
      F.live,
      F.livedAt,
      F.likes,
      F.language,
      F.quality
    )
  private val postProjection = Projection.include(interestedFields)

  private val interestedEventFields =
    List("operationType", "clusterTime", "documentKey._id") ++ interestedFields.map("fullDocument." + _)
  private val eventProjection = Projection.include(interestedEventFields)

  private def aggregate() =
    Aggregate
      .matchBy(Filter.in("operationType", interestedOperations))
      .combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], config: IngestorConfig.Ublog)(using
      LoggerFactory[IO]
  ): IO[Repo[DbUblog]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    mongo.getCollectionWithCodec[DbUblog]("ublog_post").map(apply(config))

  def apply(config: IngestorConfig.Ublog)(
      posts: MongoCollection[IO, DbUblog]
  )(using Logger[IO]): Repo[DbUblog] = new:

    def fetch(since: Instant, until: Instant) =
      val filter = range(F.livedAt)(since, until.some)
      fs2.Stream.eval(info"Fetching blog posts from $since to $until") *>
        posts
          .find(filter)
          .projection(postProjection)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList)
          .metered(1.second)
          .map: docs =>
            val (toDelete, toIndex) = docs.partition(!_.isLive)
            Result(toIndex.map(doc => doc.id -> doc), toDelete.map(doc => Id(doc.id)), none)

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[DbUblog]] =
      val builder = posts.watch(aggregate())
      // skip the first event if we're starting from a specific timestamp
      // since the event at that timestamp is already indexed
      val skip = since.fold(0)(_ => 1)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .fullDocument(FullDocument.UPDATE_LOOKUP) // this is required for update event
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .drop(skip)
        .evalTap(x => debug"Ublog event: $x")
        .groupWithin(config.batchSize, config.timeWindows.second)
        .map(_.toList.distincByDocId)
        .map: docs =>
          val lastEventTimestamp = docs.flatten(using _.clusterTime.flatMap(_.asInstant)).maxOption
          val (toDelete, toIndex) = docs.partition(_.isDelete)
          Result(
            toIndex.flatten(using _.fullDocument).map(doc => doc.id -> doc),
            toDelete.flatten(using _.docId.map(Id.apply)),
            lastEventTimestamp
          )

    extension (event: ChangeStreamDocument[DbUblog])
      private def isDelete: Boolean =
        event.operationType == DELETE || event.fullDocument.exists(!_.isLive)

  object F:
    val markdown = "markdown"
    val title = "title"
    val intro = "intro"
    val blog = "blog"
    val language = "language"
    val likes = "likes"
    val live = "live"
    val livedAt = "lived.at"
    val quality = "automod.quality"
    val topics = "topics"

case class DbUblog(
    id: String, // _id
    title: String,
    intro: String,
    markdown: String,
    blog: String, // format: "user:authorId"
    language: String,
    likes: Int,
    topics: List[String],
    live: Boolean,
    livedAt: Option[Instant], // lived.at
    quality: Option[Int] // automod.quality
):
  def isLive: Boolean = live && !quality.exists(_ == 0)
  def author: String = blog.split(":")(1)

object DbUblog:
  import UblogRepo.F
  given Decoder[DbUblog] =
    Decoder.forProduct11(
      _id,
      F.title,
      F.intro,
      F.markdown,
      F.blog,
      F.language,
      F.likes,
      F.topics,
      F.live,
      F.livedAt,
      F.quality
    )(DbUblog.apply)

  // We don't write to the database so we don't need to implement this
  given Encoder[DbUblog] = new:
    def apply(a: DbUblog): Json = ???
