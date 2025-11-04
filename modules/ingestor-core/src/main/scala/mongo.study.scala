package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import mongo4cats.bson.Document
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
  private val deleteDocProjection = Projection.include(F.oplogId)

  def apply(
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      config: IngestorConfig.Study
  )(using LoggerFactory[IO]): IO[Repo[(Document, StudyData)]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (study.getCollection("study"), ChapterRepo(study), local.getCollection("oplog.rs"))
      .mapN(apply(config))

  def apply(config: IngestorConfig.Study)(
      studies: MongoCollection,
      chapters: ChapterRepo,
      oplogs: MongoCollection
  )(using Logger[IO]): Repo[(Document, StudyData)] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[(Document, StudyData)]] =
      intervalStream(since)
        .meteredStartImmediately(config.interval)
        .flatMap(fetch)

    def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[(Document, StudyData)]] =
      fs2.Stream.eval(info"Fetching studies from $since to $until") *>
        pullForIndex(since, until)
          .merge(pullForDelete(since, until))
        ++ fs2.Stream(Result(Nil, Nil, until.some))

    def pullForIndex(since: Instant, until: Instant): fs2.Stream[IO, Result[(Document, StudyData)]] =
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
        .map(Result(_, Nil, none))

    def pullForDelete(since: Instant, until: Instant): fs2.Stream[IO, Result[(Document, StudyData)]] =
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
        .map(Result(Nil, _, none))

    def extractId(doc: Document): Option[Id] =
      doc.getNestedAs[String](F.oplogId).map(Id.apply)

    def intervalStream(startAt: Option[Instant]): fs2.Stream[IO, (Instant, Instant)] =
      (startAt.fold(fs2.Stream.empty)(since => fs2.Stream(since))
        ++ fs2.Stream
          .eval(IO.realTimeInstant)
          .flatMap(now =>
            fs2.Stream.unfold(now)(s => (s, s.plusSeconds(config.interval.toSeconds)).some)
          )).zipWithNext
        .map((since, until) => since -> until.get)

    extension (docs: List[Document])
      private def toData: IO[List[SourceWithId[(Document, StudyData)]]] =
        val studyIds = docs.flatMap(_.id).distinct
        chapters
          .byStudyIds(studyIds)
          .flatMap: chapterMap =>
            docs.traverseFilter(_.toData(chapterMap))

    extension (doc: Document)
      private def toData(
          chapterMap: Map[String, StudyData]
      ): IO[Option[SourceWithId[(Document, StudyData)]]] =
        doc.id
          .flatMap: id =>
            chapterMap.get(id).map(data => id -> (doc, data))
          .pure[IO]
          .flatTap: data =>
            def reason =
              doc.id.fold("missing doc._id; ")(_ => "")
                + (if chapterMap.contains(doc.id.getOrElse("")) then "" else "missing chapter data; ")
            info"failed to prepare document data: $doc because $reason".whenA(data.isEmpty)

  object F:
    val name = "name"
    val likes = "likes"
    val members = "members"
    val ownerId = "ownerId"
    val visibility = "visibility"
    val topics = "topics"
    val createdAt = "createdAt"
    val updatedAt = "updatedAt"
    val oplogId = "o._id"
    val rank = "rank"
