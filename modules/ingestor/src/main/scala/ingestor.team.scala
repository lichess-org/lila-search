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

trait TeamIngestor:
  // watch change events from MongoDB and ingest team data into elastic search
  def watch: fs2.Stream[IO, Unit]
  // Fetch teams in [since, until] and ingest into elastic search
  def run(since: Instant, until: Instant, dryRun: Boolean): fs2.Stream[IO, Unit]

object TeamIngestor:

  private val index = Index.Team

  private val interestedOperations = List(DELETE, INSERT, UPDATE, REPLACE).map(_.getValue)
  private val eventFilter          = Filter.in("operationType", interestedOperations)

  private val interestedFields = List("_id", F.name, F.description, F.nbMembers, F.name, F.enabled)
  private val postProjection   = Projection.include(interestedFields)

  private val interestedEventFields =
    List("operationType", "clusterTime", "documentKey._id") ++ interestedFields.map("fullDocument." + _)
  private val eventProjection = Projection.include(interestedEventFields)

  private val aggregate = Aggregate.matchBy(eventFilter).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Team)(
      using LoggerFactory[IO]
  ): IO[TeamIngestor] =
    given Logger[IO] = summon[LoggerFactory[IO]].getLogger
    mongo.getCollection("team").map(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Team)(teams: MongoCollection)(using
      Logger[IO]
  ): TeamIngestor = new:
    def watch =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting team ingestor from $since"))
        .flatMap: last =>
          changeStream(last)
            .filterNot(_.isEmpty)
            .evalMap: events =>
              val lastEventTimestamp  = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
              val (toDelete, toIndex) = events.partition(_.isDelete)
              storeBulk(toIndex.flatten(_.fullDocument))
                *> elastic.deleteMany(index, toDelete)
                *> saveLastIndexedTimestamp(lastEventTimestamp.getOrElse(Instant.now))

    def run(since: Instant, until: Instant, dryRun: Boolean) =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
        .or(range(F.erasedAt)(since, until.some))
      teams
        .find(filter)
        .projection(postProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList)
        .metered(1.second) // to avoid overloading the elasticsearch
        .evalMap: docs =>
          val (toDelete, toIndex) = docs.partition(!_.isEnabled)
          dryRun.fold(
            toIndex.traverse_(doc => debug"Would index $doc")
              *> toDelete.traverse_(doc => debug"Would delete $doc"),
            storeBulk(toIndex) *> elastic.deleteMany(index, toDelete)
          )

    private def storeBulk(docs: List[Document]): IO[Unit] =
      val sources = docs.toSources
      info"Received ${docs.size} teams to index" *>
        elastic
          .storeBulk(index, sources)
          .handleErrorWith: e =>
            Logger[IO].error(e)(s"Failed to index teams: ${docs.map(_.id).mkString(", ")}")
          .whenA(sources.nonEmpty)
        *> info"Indexed ${sources.size} teams"

    private def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(_.some.pure[IO])

    private def changeStream(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
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

    extension (docs: List[Document])
      private def toSources: List[(String, TeamSource)] =
        docs.flatten(doc => (doc.id, doc.toSource).mapN(_ -> _))

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
