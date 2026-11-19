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

  private val blogEventFields =
    List(
      "operationType",
      "clusterTime",
      "documentKey._id",
      "fullDocument._id",
      "fullDocument.tier"
    )
  private val blogEventProjection = Projection.include(blogEventFields)
  private val blogEventFilter =
    Filter.in("operationType", List(INSERT, REPLACE, UPDATE).map(_.getValue))

  private def aggregate() =
    Aggregate
      .matchBy(Filter.in("operationType", interestedOperations))
      .combinedWith(Aggregate.project(eventProjection))

  private def blogAggregate() =
    Aggregate.matchBy(blogEventFilter).combinedWith(Aggregate.project(blogEventProjection))

  def apply(mongo: MongoDatabase[IO], config: IngestorConfig.Ublog)(using
      LoggerFactory[IO]
  ): IO[Repo[DbUblog]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (
      mongo.getCollectionWithCodec[DbUblog]("ublog_post"),
      mongo.getCollectionWithCodec[BlogTier]("ublog_blog"),
      mongo.getCollectionWithCodec[EnabledUser]("user4")
    ).mapN: (posts, blogs, users) =>
      val data = UblogData(posts, blogs, users)
      new UblogPostRepo(config, data)

  def blogTierRepo(mongo: MongoDatabase[IO], config: IngestorConfig.Ublog)(using
      LoggerFactory[IO]
  ): IO[Repo[DbUblog]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (
      mongo.getCollectionWithCodec[DbUblog]("ublog_post"),
      mongo.getCollectionWithCodec[BlogTier]("ublog_blog"),
      mongo.getCollectionWithCodec[EnabledUser]("user4")
    ).mapN: (posts, blogs, users) =>
      UblogBlogTierRepo(config, UblogData(posts, blogs, users))

  private class UblogPostRepo(config: IngestorConfig.Ublog, data: UblogData)(using Logger[IO])
      extends Repo[DbUblog]:

    def fetchAll(since: Instant, until: Instant) =
      val filter = range(F.livedAt)(since, until.some)
      fs2.Stream.eval(info"Fetching blog posts from $since to $until") *>
        data.posts
          .find(filter)
          .projection(postProjection)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList)
          .metered(1.second)
          .evalMap(docs => data.enrich(docs))
          .map: docs =>
            val (toDelete, toIndex) = docs.partition(!_.visible)
            Result(toIndex, toDelete.map(doc => Id(doc.id)), None)

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[DbUblog]] =
      val builder = data.posts.watch(aggregate())
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
        .evalMap: docs =>
          val lastEventTimestamp = docs.flatten(using _.clusterTime.flatMap(_.asInstant)).maxOption
          val (toDelete, toIndex) = docs.partition(_.isDelete)
          data
            .enrich(toIndex.flatMap(_.fullDocument))
            .map: indexed =>
              Result(
                indexed,
                toDelete.flatMap(_.docId.map(Id.apply)),
                lastEventTimestamp
              )

  private class UblogBlogTierRepo(config: IngestorConfig.Ublog, data: UblogData)(using Logger[IO])
      extends Repo[DbUblog]:

    // Re-indexes every post of a blog when lila changes its tier. Account closure sets
    // the tier to HIDDEN, so this also hides posts of deleted accounts in real time.
    def watch(since: Option[Instant]): fs2.Stream[IO, Result[DbUblog]] =
      val builder = data.blogs.watch(blogAggregate())
      val skip = since.fold(0)(_ => 1)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .fullDocument(FullDocument.UPDATE_LOOKUP)
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .drop(skip)
        .evalTap(x => debug"Ublog blog event: $x")
        .groupWithin(config.batchSize, config.timeWindows.second)
        .map(_.toList.distincByDocId)
        .evalMap: docs =>
          val timestamp = docs.flatten(using _.clusterTime.flatMap(_.asInstant)).maxOption
          val tiers = docs
            .flatMap(_.fullDocument)
            .map(blog => blog.id -> blog.tier)
            .groupMap(_._1)(_._2)
            .view
            .mapValues(_.last)
            .toMap
          tiers.toList
            .traverse: (blogId, tier) =>
              data.postsForBlog(blogId).flatMap(data.enrich(_, Map(blogId -> tier)))
            .map(docs => Result(docs.flatten, Nil, timestamp))

    def fetchAll(since: Instant, until: Instant): fs2.Stream[IO, Result[DbUblog]] =
      fs2.Stream.empty

  extension (event: ChangeStreamDocument[DbUblog])
    private def isDelete: Boolean =
      event.operationType == DELETE || event.fullDocument.exists(!_.isLive)

  private class UblogData(
      val posts: MongoCollection[IO, DbUblog],
      val blogs: MongoCollection[IO, BlogTier],
      users: MongoCollection[IO, EnabledUser]
  ):

    def enrich(
        blogPosts: List[DbUblog],
        tierOverrides: Map[String, Int] = Map.empty
    ): IO[List[DbUblog]] =
      if blogPosts.isEmpty then Nil.pure[IO]
      else
        for
          tiers <- blogTiers(blogPosts.map(_.blog).distinct)
          enabled <- enabledUsers(blogPosts.map(_.author).distinct)
        yield blogPosts.map: post =>
          post.copy(
            blogTier = tierOverrides.get(post.blog).orElse(tiers.get(post.blog)),
            userEnabled = enabled.contains(post.author)
          )

    def postsForBlog(blogId: String): IO[List[DbUblog]] =
      posts
        .find(Filter.eq(F.blog, blogId))
        .projection(postProjection)
        .stream
        .compile
        .toList

    private def blogTiers(blogIds: List[String]): IO[Map[String, Int]] =
      if blogIds.isEmpty then Map.empty[String, Int].pure[IO]
      else
        blogs
          .find(Filter.in(_id, blogIds))
          .projection(Projection.include(List(_id, "tier")))
          .stream
          .map(blog => blog.id -> blog.tier)
          .compile
          .to(Map)

    private def enabledUsers(userIds: List[String]): IO[Set[String]] =
      if userIds.isEmpty then Set.empty[String].pure[IO]
      else
        users
          .find(Filter.in(_id, userIds).and(Filter.eq("enabled", true)))
          .projection(Projection.include(_id))
          .stream
          .map(_.id)
          .compile
          .to(Set)

  private case class BlogTier(id: String, tier: Int)
  private object BlogTier:
    given Decoder[BlogTier] = Decoder.instance: c =>
      for
        id <- c.get[String](_id)
        tier <- c.get[Int]("tier")
      yield BlogTier(id, tier)
    given Encoder[BlogTier] = Encoder.forProduct2(_id, "tier")(b => (b.id, b.tier))

  private case class EnabledUser(id: String)
  private object EnabledUser:
    given Decoder[EnabledUser] = Decoder.forProduct1(_id)(EnabledUser.apply)
    given Encoder[EnabledUser] = Encoder.forProduct1(_id)(_.id)

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
    quality: Option[Int], // automod.quality
    blogTier: Option[Int] = None, // joined from ublog_blog
    userEnabled: Boolean = true // joined from user4
):
  def isLive: Boolean = live && !quality.exists(_ == 0)
  def author: String = blog.split(":")(1)
  def visible: Boolean = isLive && blogTier.exists(_ >= 1) && userEnabled

object DbUblog:
  import UblogRepo.F
  given Decoder[DbUblog] = Decoder.instance: c =>
    for
      id <- c.get[String](_id)
      title <- c.get[String](F.title)
      intro <- c.get[String](F.intro)
      markdown <- c.get[String](F.markdown)
      blog <- c.get[String](F.blog)
      language <- c.get[String](F.language)
      likes <- c.get[Int](F.likes)
      topics <- c.get[List[String]](F.topics)
      live <- c.get[Boolean](F.live)
      livedAt <- c.downField("lived").get[Option[Instant]]("at")
      quality <- c.downField("automod").get[Option[Int]]("quality")
    yield DbUblog(id, title, intro, markdown, blog, language, likes, topics, live, livedAt, quality)

  // We don't write to the database so we don't need to implement this
  given Encoder[DbUblog] = new:
    def apply(a: DbUblog): Json = ???
