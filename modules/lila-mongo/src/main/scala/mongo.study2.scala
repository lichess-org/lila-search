package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import mongo4cats.bson.Document
import mongo4cats.circe.*
import mongo4cats.collection.MongoCollection
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Filter, Projection }
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

import Repo.*
import scala.annotation.nowarn

object Study2Repo:
  import StudyRepo.F

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
  )(using LoggerFactory[IO]): IO[Repo[DbStudy]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (study.getCollectionWithCodec[DbStudy]("study"), local.getCollection("oplog.rs"))
      .mapN(apply(config))

  def apply(config: IngestorConfig.Study)(
      studies: MongoCollection[IO, DbStudy],
      oplogs: MongoCollection[IO, Document]
  )(using Logger[IO]): Repo[DbStudy] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[DbStudy]] =
      intervalStream(since)
        .meteredStartImmediately(config.interval)
        .flatMap(fetch)

    def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[DbStudy]] =
      fs2.Stream.eval(info"Fetching studies from $since to $until") *>
        pullForIndex(since, until)
          .merge(pullForDelete(since, until))
        // .merge(pullForLikes(since, until))
        ++ fs2.Stream(Result(Nil, Nil, until.some))

    def pullForIndex(since: Instant, until: Instant): fs2.Stream[IO, Result[DbStudy]] =
      // filter out relay: https://github.com/lichess-org/lila/blob/d1ebb8bdc744125d0024fa643b3817fa34814035/modules/study/src/main/BSONHandlers.scala#L392
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
      studies
        .find(filter)
        .projection(indexDocProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList)
        .map(Result(_, Nil, None))

    def pullForDelete(since: Instant, until: Instant): fs2.Stream[IO, Result[DbStudy]] =
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
        .map(Result(Nil, _, None))

    @nowarn("msg=unused") // currently not used as we don't support partial updates in study index
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

    def intervalStream(startAt: Option[Instant]): fs2.Stream[IO, (Instant, Instant)] =
      (startAt.fold(fs2.Stream.empty)(since => fs2.Stream(since))
        ++ fs2.Stream
          .eval(IO.realTimeInstant)
          .flatMap(now =>
            fs2.Stream.unfold(now)(s => (s, s.plusSeconds(config.interval.toSeconds)).some)
          )).zipWithNext
        .map((since, until) => since -> until.get)
