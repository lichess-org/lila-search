package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType.*
import lila.search.spec.StudySource
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.{ Aggregate, Filter, Projection }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

import java.time.Instant
import scala.concurrent.duration.*

trait StudyIngestor:
  // watch change events from MongoDB and ingest studies data into elastic search
  def watch: fs2.Stream[IO, Unit]

object StudyIngestor:

  private val index = Index.Study

  private val interestedOperations = List(DELETE, INSERT, UPDATE, REPLACE).map(_.getValue)
  private val eventFilter          = Filter.in("operationType", interestedOperations)

  // private val interestedFields = List("_id", F.name, F.description, F.nbMembers, F.name, F.enabled)

  private val interestedEventFields =
    List(
      "operationType",
      "clusterTime",
      "documentKey._id",
      "fullDocument"
    ) // ++ interestedFields.map("fullDocument." + _)
  private val eventProjection = Projection.include(interestedEventFields)

  private val aggregate = Aggregate.matchBy(eventFilter).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Study)(
      using Logger[IO]
  ): IO[StudyIngestor] =
    (mongo.getCollection("study"), ChapterRepo(mongo))
      .mapN(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Study)(
      studies: MongoCollection,
      chapters: ChapterRepo
  )(using
      Logger[IO]
  ): StudyIngestor = new:
    def watch =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting studies ingestor from $since"))
        .flatMap: last =>
          changeStream(last)
            .filterNot(_.isEmpty)
            .evalMap: events =>
              // val lastEventTimestamp  = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
              val (toDelete, toIndex) = events.partition(_.isDelete)
              val studyIds            = events.flatten(_.docId).distinct
              IO.println(s"distinc study ids: $studyIds") >>
                chapters.byStudyIds(studyIds) >>= IO.println
    // storeBulk(toIndex.flatten(_.fullDocument))
    //   *> elastic.deleteMany(index, toDelete)
    //   *> saveLastIndexedTimestamp(lastEventTimestamp.getOrElse(Instant.now))

    def storeBulk(docs: List[Document]): IO[Unit] =
      info"Received ${docs.size} studies to index" *>
        docs.toSources.flatMap: sources =>
          elastic.storeBulk(index, sources) *> info"Indexed ${sources.size} studies"
            .handleErrorWith: e =>
              Logger[IO].error(e)(s"Failed to index studies: ${docs.map(_.id).mkString(", ")}")
            .whenA(docs.nonEmpty)

    def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(Instant.ofEpochSecond(_).some.pure[IO])

    private def changeStream(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[Document]]] =
      // skip the first event if we're starting from a specific timestamp
      // since the event at that timestamp is already indexed
      val skip    = since.fold(0)(_ => 1)
      val builder = studies.watch(aggregate)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .batchSize(config.batchSize)
        .fullDocument(FullDocument.UPDATE_LOOKUP) // this is required for update event
        .boundedStream(config.batchSize)
        .drop(skip)
        // .evalTap(x => debug"Study change stream event: $x")
        .groupWithin(config.batchSize, config.timeWindows.second)
        .map(_.toList.unique)

    // TOOD: only need to index the latest study
    // We should do that in changeStream by using some fs2 operators
    extension (docs: List[Document])
      private def toSources: IO[List[StudySourceWithId]] =
        val studyIds = docs.flatMap(_.id).distinct
        chapters
          .byStudyIds(studyIds)
          .flatMap: chapters =>
            docs
              .traverse(_.toSource(chapters))
              .map(_.flatten)

// final case class StudySource(
//     name: String,
//     owner: String,
//     members: List[String],
//     chapterNames: String,
//     chapterTexts: String,
//     likes: Int,
//     public: Boolean,
//     topics: List[String] = List()
// )

    type StudySourceWithId = (String, StudySource)
    extension (doc: Document)
      private def toSource(chapters: Map[String, StudyChapterText]): IO[Option[StudySourceWithId]] = ???
      // val id = doc.id
      // val name = doc.getString(Study.name)
      // val ownerId = doc.getString(Study.ownerId)
      // val members = doc.getList(Study.members).map(_.flatMap(_.asString))
      // val description = doc.getString(Study.description)
      // val likes = doc.getInt(Study.likes)
      // val chapterIds = doc.getList(Chapter._id).map(_.flatMap(_.asString))
      // val chapterNames = chapterIds.flatMap(_.flatMap(chapters.get).map(_.name))
      // val chapterTexts = chapterIds.flatMap(_.flatMap(chapters.get).map(_.comments))
      // (id, name, ownerId, members, description, likes, chapterNames, chapterTexts)
      //   .mapN(StudySource.apply)
      //   .map(id -> _)
      //   .pure[IO]

    extension (event: ChangeStreamDocument[Document])
      private def isDelete: Boolean =
        event.operationType == DELETE
      // event.fullDocument.fold(false)(x => !x.isEnabled)

  object Study:
    val name        = "name"
    val likes       = "likes"
    val members     = "members"
    val ownerId     = "ownerId"
    val description = "description"
