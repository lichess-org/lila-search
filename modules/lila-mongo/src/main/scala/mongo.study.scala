package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import mongo4cats.bson.Document
import mongo4cats.circe.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

import Repo.*

object StudyRepo:

  def apply(
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      config: IngestorConfig.Study
  )(using LoggerFactory[IO]): IO[Repo[(DbStudy, StudyChapterData)]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (Study2Repo(study, local, config), ChapterRepo(study))
      .mapN(apply)

  def apply(
      study2: Repo[DbStudy],
      chapters: ChapterRepo
  )(using Logger[IO]): Repo[(DbStudy, StudyChapterData)] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
      study2.watch(since).evalMap(enrichWithChapters)

    def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
      study2.fetch(since, until).evalMap(enrichWithChapters)

    private def enrichWithChapters(result: Result[DbStudy]): IO[Result[(DbStudy, StudyChapterData)]] =
      val studyIds = result.toIndex.map(_.id).distinct
      chapters
        .byStudyIds(studyIds)
        .flatMap: chapterMap =>
          result.toIndex
            .traverseFilter(_.toData(chapterMap))
            .map: enriched =>
              Result(
                enriched,
                result.toDelete,
                // result.toUpdate,
                result.timestamp
              )

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
    val from = "rank"
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
    updatedAt: Option[Instant],
    from: Option[String] = None
):
  def memberIds: List[String] = members.fold(Nil)(_.keys.toList)

object DbStudy:
  import StudyRepo.F
  given Decoder[DbStudy] =
    Decoder.forProduct11(
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
      F.from
    )(DbStudy.apply)

  // We don't write to the database so we don't need to implement this
  given Encoder[DbStudy] = new:
    def apply(a: DbStudy): Json = ???
