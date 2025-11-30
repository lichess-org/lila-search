package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import mongo4cats.bson.Document
import mongo4cats.circe.*
import mongo4cats.collection.MongoCollection
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Filter, Projection }
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

import Repo.*

object Study2Repo:

  private val interestedfields =
    List(
      "_id",
      F.name,
      F.members,
      F.ownerId,
      F.visibility,
      F.topics,
      F.likes,
      F.rank,
      F.createdAt,
      F.updatedAt,
      F.from,
      F.views,
      F.description
    )

  private val indexDocProjection = Projection.include(interestedfields)
  private val deleteDocProjection = Projection.include(F.oplogDeleteId)
  private val likesDocProjection = Projection.include(List(F.oplogUpdateId, F.oplogLikes, F.oplogRank))

  def apply(
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      config: IngestorConfig.Study
  )(using LoggerFactory[IO]): IO[Repo[DbStudy]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (study.getCollectionWithCodec[DbStudy]("study"), local.getCollection("oplog.rs"))
      .mapN(apply(config))

  def apply(config: IngestorConfig.Study)(
      studies: MongoCollection[IO, DbStudy],
      oplogs: MongoCollection[IO, Document]
  )(using Logger[IO]): Repo[DbStudy] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[DbStudy]] =
      StreamUtils
        .intervalStream(since, config.interval)
        .meteredStartImmediately(config.interval)
        .flatMap(fetchAll)

    def fetchAll(since: Instant, until: Instant): fs2.Stream[IO, Result[DbStudy]] =
      fs2.Stream.eval(info"Fetching studies from $since to $until") *>
        pullForIndex(since, until)
          .map(Result(_, Nil, None))
          .merge(pullForDelete(since, until).map(Result(Nil, _, None)))
          .merge(pullForLikes(since, until).map(Result(Nil, Nil, _, None)))
        ++ fs2.Stream(Result(Nil, Nil, until.some))

    override def fetchUpdate(since: Instant, until: Instant): fs2.Stream[IO, List[DbStudy]] =
      fs2.Stream.eval(info"Fetching created/updated studies from $since to $until") *>
        pullForIndex(since, until)

    override def fetchDelete(since: Instant, until: Instant): fs2.Stream[IO, List[Id]] =
      fs2.Stream.eval(info"Fetching deleted studies from $since to $until") *>
        pullForDelete(since, until)

    def pullForIndex(since: Instant, until: Instant): fs2.Stream[IO, List[DbStudy]] =
      // filter out relay: https://github.com/lichess-org/lila/blob/d1ebb8bdc744125d0024fa643b3817fa34814035/modules/study/src/main/BSONHandlers.scala#L392
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
      studies
        .find(filter)
        .projection(indexDocProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.filter(!_.isBroadcast))
        .map(_.toList)
        .evalTap(_.traverse_(s => debug"Indexing study $s"))

    def pullForDelete(since: Instant, until: Instant): fs2.Stream[IO, List[Id]] =
      val filter =
        Filter
          .gte("ts", since.asBsonTimestamp)
          .and(Filter.lt("ts", until.asBsonTimestamp))
          .and(Filter.eq("ns", s"${config.databaseName}.study"))
          .and(Filter.eq("op", "d"))
      oplogs
        .find(filter)
        .projection(deleteDocProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList.flatMap(extractId))
      // .evalTap(xs => info"Deleting $xs")

    def pullForLikes(since: Instant, until: Instant) = // fs2.Stream[IO, Result[DbStudy]] =
      val filter =
        Filter
          .gte("ts", since.asBsonTimestamp)
          .and(Filter.lt("ts", until.asBsonTimestamp))
          .and(Filter.eq("ns", s"${config.databaseName}.study"))
          .and(Filter.eq("op", "u")) // update operation
          .and(Filter.exists("o.diff.u.likes")) // where likes changed
      oplogs
        .find(filter)
        .projection(likesDocProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList.flatMap(StudyLikesOnly.fromDoc).distincByDocId)
        .map(_.map(l => l.id -> l.toMap))
      // .map(Result(Nil, Nil,  None))

    def extractId(doc: Document): Option[Id] =
      doc.getNestedAs[String](F.oplogDeleteId).map(Id.apply)

  object F:
    val name = "name"
    val likes = "likes"
    val members = "members"
    val ownerId = "ownerId"
    val visibility = "visibility"
    val topics = "topics"
    val createdAt = "createdAt"
    val updatedAt = "updatedAt"
    val rank = "rank"
    val from = "from"
    val views = "views"
    val description = "description"
    val oplogDeleteId = "o._id"
    val oplogUpdateId = "o2._id"
    val oplogLikes = "o.diff.u.likes"
    val oplogRank = "o.diff.u.rank"

case class StudyLikesOnly(id: Id, likes: Int, rank: Option[Instant]):
  inline def toMap: Map[String, Any] =
    Map("likes" -> likes) ++ rank.map(r => "rank" -> SearchDateTime.fromInstant(r))

object StudyLikesOnly:
  import Study2Repo.F
  given HasDocId[StudyLikesOnly] with
    extension (a: StudyLikesOnly) def docId: Option[String] = Some(a.id.value)

  def fromDoc(doc: Document): Option[StudyLikesOnly] =
    val id = doc.getNestedAs[String](F.oplogUpdateId).map(Id.apply)
    val rank = doc.getNestedAs[Instant](F.oplogRank)
    val likes = doc.getNestedAs[Int](F.oplogLikes)
    (id, likes).mapN(StudyLikesOnly(_, _, rank))

case class DbStudy(
    id: String, // _id
    name: String,
    ownerId: String,
    members: Option[Map[String, Json]], // Map where we only care about keys
    visibility: Option[String],
    topics: Option[List[String]],
    likes: Option[Int],
    rank: Option[Instant],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
    from: Option[String],
    views: Option[Int],
    description: Option[String]
):
  def memberIds: List[String] = members.fold(Nil)(_.keys.toList)
  // https://github.com/lichess-org/lila/blob/71f127800da448ef79f7a5f868c16608110c9c7c/modules/study/src/main/BSONHandlers.scala#L377
  def isBroadcast: Boolean = from.exists(_.startsWith("relay"))

object DbStudy:
  import Study2Repo.F
  given Decoder[DbStudy] =
    Decoder.forProduct13(
      _id,
      F.name,
      F.ownerId,
      F.members,
      F.visibility,
      F.topics,
      F.likes,
      F.rank,
      F.createdAt,
      F.updatedAt,
      F.from,
      F.views,
      F.description // todo filter out bad description?
    )(DbStudy.apply)

  // We don't write to the database so we don't need to implement this
  given Encoder[DbStudy] = new:
    def apply(a: DbStudy): Json = ???
