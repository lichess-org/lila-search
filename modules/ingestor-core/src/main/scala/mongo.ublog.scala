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
  ): IO[Repo[Document]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    mongo.getCollection("ublog_post").map(apply(config))

  def apply(config: IngestorConfig.Ublog)(
      posts: MongoCollection
  )(using Logger[IO]): Repo[Document] = new:

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
            Result(toIndex.map(doc => doc.id.get -> doc), toDelete.flatten(using _.id.map(Id.apply)), none)

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[Document]] =
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
            toIndex.flatten(using _.fullDocument).map(doc => doc.id.get -> doc),
            toDelete.flatten(using _.docId.map(Id.apply)),
            lastEventTimestamp
          )

    extension (doc: Document)
      private def isLive: Boolean =
        doc.getBoolean("live").contains(true) && !doc.getNestedAs[Int](F.quality).exists(_ == 0)

    extension (event: ChangeStreamDocument[Document])
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
