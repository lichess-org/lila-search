package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.mongodb.client.model.changestream.OperationType.*
import lila.search.spec.TeamSource
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.{ Aggregate, Filter }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

import java.time.Instant
import scala.concurrent.duration.*

trait TeamIngestor:
  def run(): fs2.Stream[IO, Unit]

object TeamIngestor:

  // def data = TeamData(id, name, description, nbMembers, createdBy)
  private val interestedOperations = List(DELETE, INSERT, UPDATE, REPLACE).map(_.getValue)
  private val eventFilter          = Filter.in("operationType", interestedOperations)
  // private val eventProjection = Projection.include(
  //   List(
  //     "documentKey._id",
  //     "fullDocument.name",
  //     "fullDocument.description",
  //     "fullDocument.nbMembers",
  //     "fullDocument.createdBy",
  //   )
  // )
  private val aggregate = Aggregate.matchBy(eventFilter) // .combinedWith(Aggregate.project(eventProjection))

  private val index = Index.Team

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Team)(
      using Logger[IO]
  ): IO[TeamIngestor] =
    mongo.getCollection("team").map(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Team)(teams: MongoCollection)(using
      Logger[IO]
  ): TeamIngestor = new:
    def run() =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting team ingestor from $since"))
        .flatMap: last =>
          changeStream(last)
            .filterNot(_.isEmpty)
            .evalMap: events =>
              val lastEventTimestamp  = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
              val (toDelete, toIndex) = events.partition(_.isDelete)
              storeBulk(toIndex)
                *> deleteMany(toDelete)
                *> saveLastIndexedTimestamp(lastEventTimestamp.getOrElse(Instant.now()))

    private def storeBulk(events: List[ChangeStreamDocument[Document]]): IO[Unit] =
      info"Received ${events.size} teams to index"
        *> elastic.storeBulk(index, events.toSources)
        *> info"Indexed ${events.size} teams"
          .handleErrorWith: e =>
            Logger[IO].error(e)(s"Failed to index team: ${events.map(_.id).mkString(", ")}")

    private def deleteMany(events: List[ChangeStreamDocument[Document]]): IO[Unit] =
      elastic
        .deleteMany(index, events.flatMap(_.id.map(Id.apply)))
        .flatTap(_ => info"Deleted ${events.size} teams")
        .handleErrorWith: e =>
          Logger[IO].error(e)(s"Failed to delete teams: ${events.map(_.id).mkString(", ")}")

    private def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(Instant.ofEpochSecond(_).some.pure[IO])

    private def changeStream(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      val builder = teams.watch(aggregate)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .evalTap(IO.println)
        .evalTap(x => IO.println(x.fullDocument))
        .groupWithin(config.batchSize, config.timeWindows.second)
        .map(_.toList)

    extension (events: List[ChangeStreamDocument[Document]])
      private def toSources: List[(String, TeamSource)] =
        events.flatten(event => (event.id, event.fullDocument.flatMap(_.toSource)).mapN(_ -> _))

    extension (doc: Document)
      private def toSource: Option[TeamSource] =
        (
          doc.getString("name"),
          doc.getString("description"),
          doc.getInt("nbMembers")
        ).mapN(TeamSource.apply)

    extension (event: ChangeStreamDocument[Document])
      private def isDelete: Boolean =
        event.operationType == DELETE ||
          event.fullDocument.flatMap(_.get("enabled")).contains(false)
