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

object StudyRepo:

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
      F.updatedAt
    )

  private val indexDocProjection = Projection.include(interestedfields)
  private val deleteDocProjection = Projection.include(F.oplogDeleteId)
  private val likesDocProjection = Projection.include(List(F.oplogUpdateId, F.oplogLikes, F.oplogRank))

  def apply(
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      config: IngestorConfig.Study
  )(using LoggerFactory[IO]): IO[Repo[(DbStudy, StudyChapterData)]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (study.getCollectionWithCodec[DbStudy]("study"), ChapterRepo(study), local.getCollection("oplog.rs"))
      .mapN(apply(config))

  def apply(config: IngestorConfig.Study)(
      studies: MongoCollection[IO, DbStudy],
      chapters: ChapterRepo,
      oplogs: MongoCollection[IO, Document]
  )(using Logger[IO]): Repo[(DbStudy, StudyChapterData)] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
      intervalStream(since)
        .meteredStartImmediately(config.interval)
        .flatMap(fetch)

    def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
      fs2.Stream.eval(info"Fetching studies from $since to $until") *>
        pullForIndex(since, until)
          .merge(pullForDelete(since, until))
          .merge(pullForLikes(since, until))
        ++ fs2.Stream(Result(Nil, Nil, Nil, until.some))

    def pullForIndex(since: Instant, until: Instant): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
      studies
        .find(filter)
        .projection(indexDocProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList)
        // .evalTap(_.traverse_(x => debug"received $x"))
        .evalMap(_.toData)
        .map(Result(_, Nil, Nil, None))

    def pullForDelete(since: Instant, until: Instant): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
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
        .evalTap(xs => info"Deleting $xs")
        .map(Result(Nil, _, Nil, None))

    def pullForLikes(since: Instant, until: Instant): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
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
        // .evalTap(_.traverse_(x => info"received $x"))
        .map(_.toList.flatMap(StudyLikesOnly.fromDoc).distincByDocId)
        // .evalTap(_.traverse_(x => info"unique $x"))
        .map(_.map(l => l.id -> l.toMap))
        // .evalTap(_.traverse_(x => info"map $x"))
        .map(Result(Nil, Nil, _, None))

    def extractId(doc: Document): Option[Id] =
      doc.getNestedAs[String](F.oplogDeleteId).map(Id.apply)

    def intervalStream(startAt: Option[Instant]): fs2.Stream[IO, (Instant, Instant)] =
      (startAt.fold(fs2.Stream.empty)(since => fs2.Stream(since))
        ++ fs2.Stream
          .eval(IO.realTimeInstant)
          .flatMap(now =>
            fs2.Stream.unfold(now)(s => (s, s.plusSeconds(config.interval.toSeconds)).some)
          )).zipWithNext
        .map((since, until) => since -> until.get)

    extension (docs: List[DbStudy])
      private def toData: IO[List[(DbStudy, StudyChapterData)]] =
        val studyIds = docs.map(_.id).distinct
        chapters
          .byStudyIds(studyIds)
          .flatMap: chapterMap =>
            docs.traverseFilter(_.toData(chapterMap))

    extension (study: DbStudy)
      private def toData(
          chapterMap: Map[String, StudyChapterData]
      ): IO[Option[(DbStudy, StudyChapterData)]] =
        chapterMap
          .get(study.id)
          .map(data => (study, data))
          .pure[IO]
          .flatTap: data =>
            def reason =
              if chapterMap.contains(study.id) then "" else "missing chapter data; "
            info"failed to prepare study data for ${study.id}: $reason".whenA(data.isEmpty)

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
    val oplogDeleteId = "o._id"
    val oplogUpdateId = "o2._id"
    val oplogLikes = "o.diff.u.likes"
    val oplogRank = "o.diff.u.rank"

case class StudyLikesOnly(id: Id, likes: Int, rank: Option[Instant]):
  inline def toMap: Map[String, Any] =
    Map("likes" -> likes) ++ rank.map(r => "rank" -> SearchDateTime.fromInstant(r))

object StudyLikesOnly:
  given HasDocId[StudyLikesOnly] with
    extension (a: StudyLikesOnly) def docId: Option[String] = Some(a.id.value)

  def fromDoc(doc: Document): Option[StudyLikesOnly] =
    import StudyRepo.F
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
    updatedAt: Option[Instant]
):
  def memberIds: List[String] = members.fold(Nil)(_.keys.toList)

object DbStudy:
  import StudyRepo.F
  given Decoder[DbStudy] =
    Decoder.forProduct10(
      _id,
      F.name,
      F.ownerId,
      F.members,
      F.visibility,
      F.topics,
      F.likes,
      F.rank,
      F.createdAt,
      F.updatedAt
    )(DbStudy.apply)

  // We don't write to the database so we don't need to implement this
  given Encoder[DbStudy] = new:
    def apply(a: DbStudy): Json = ???
