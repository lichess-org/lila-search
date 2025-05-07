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

object TeamRepo:

  private val interestedOperations = List(DELETE, INSERT, UPDATE, REPLACE).map(_.getValue)
  private val eventFilter          = Filter.in("operationType", interestedOperations)

  private val interestedFields = List("_id", F.name, F.description, F.nbMembers, F.name, F.enabled)
  private val postProjection   = Projection.include(interestedFields)

  private val interestedEventFields =
    List("operationType", "clusterTime", "documentKey._id") ++ interestedFields.map("fullDocument." + _)
  private val eventProjection = Projection.include(interestedEventFields)

  private val aggregate = Aggregate.matchBy(eventFilter).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], config: IngestorConfig.Team)(using
      LoggerFactory[IO]
  ): IO[Repo[TeamSource]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    mongo.getCollection("team").map(apply(config))

  def apply(config: IngestorConfig.Team)(teams: MongoCollection)(using Logger[IO]): Repo[TeamSource] = new:

    def watch(since: Option[Instant]) =
      // skip the first event if we're starting from a specific timestamp
      // since the event at that timestamp is already indexed
      val skip    = since.fold(0)(_ => 1)
      val builder = teams.watch(aggregate)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .batchSize(config.batchSize)
        .fullDocument(FullDocument.UPDATE_LOOKUP) // this is required for update event
        .boundedStream(config.batchSize)
        .drop(skip)
        .evalTap(x => debug"Team change stream event: $x")
        .groupWithin(config.batchSize, config.timeWindows.second)
        .map(_.toList.distincByDocId)
        .map: docs =>
          val lastEventTimestamp  = docs.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
          val (toDelete, toIndex) = docs.partition(_.isDelete)
          Result(
            toIndex.flatten(using _.fullDocument).toSources,
            toDelete.flatten(using _.docId.map(Id.apply)),
            lastEventTimestamp
          )

    def fetch(since: Instant, until: Instant) =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
        .or(range(F.erasedAt)(since, until.some))
      fs2.Stream.eval(info"Fetching teams from $since to $until") *>
        teams
          .find(filter)
          .projection(postProjection)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList)
          .metered(1.second) // to avoid overloading the elasticsearch
          .map: docs =>
            val (toDelete, toIndex) = docs.partition(!_.isEnabled)
            Result(
              toIndex.toSources,
              toDelete.flatten(using _.id.map(Id.apply)),
              none
            )

    extension (docs: List[Document])
      private def toSources: List[(String, TeamSource)] =
        docs.flatten(using doc => (doc.id, doc.toSource).mapN(_ -> _))

    extension (doc: Document)
      private def toSource: Option[TeamSource] =
        (
          doc.getString(F.name),
          doc.getString(F.description),
          doc.getInt(F.nbMembers)
        ).mapN(TeamSource.apply)

      private def isEnabled =
        doc.getBoolean(F.enabled).getOrElse(true)

    extension (event: ChangeStreamDocument[Document])
      private def isDelete: Boolean =
        event.operationType == DELETE ||
          event.fullDocument.fold(false)(x => !x.isEnabled)

  object F:
    val name        = "name"
    val description = "description"
    val nbMembers   = "nbMembers"
    val enabled     = "enabled"
    val createdAt   = "createdAt"
    val updatedAt   = "updatedAt"
    val erasedAt    = "erasedAt"
