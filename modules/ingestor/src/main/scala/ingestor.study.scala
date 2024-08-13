package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import lila.search.spec.StudySource
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Filter, Projection }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

import java.time.Instant

trait StudyIngestor:
  // pull changes from study MongoDB and ingest into elastic search
  def watch: fs2.Stream[IO, Unit]
  def run(since: Instant, until: Instant, dryRun: Boolean): fs2.Stream[IO, Unit]

object StudyIngestor:

  private val index = Index.Study

  private val interestedfields = List("_id", F.name, F.members, F.ownerId, F.visibility, F.topics, F.likes)

  private val indexDocProjection  = Projection.include(interestedfields)
  private val deleteDocProjection = Projection.include(F.oplogId)

  def apply(
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      elastic: ESClient[IO],
      store: KVStore,
      config: IngestorConfig.Study
  )(using
      Logger[IO]
  ): IO[StudyIngestor] =
    (study.getCollection("study"), ChapterRepo(study), local.getCollection("oplog.rs"))
      .mapN(apply(elastic, store, config))

  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Study)(
      studies: MongoCollection,
      chapters: ChapterRepo,
      oplogs: MongoCollection
  )(using
      Logger[IO]
  ): StudyIngestor = new:
    def watch: fs2.Stream[IO, Unit] =
      intervalStream
        .meteredStartImmediately(config.interval)
        .flatMap: (since, until) =>
          run(since, until, dryRun = false)

    def run(since: Instant, until: Instant, dryRun: Boolean): fs2.Stream[IO, Unit] =
      fs2.Stream.eval(info"Indexing studies from $since to $until") ++
        pullAndIndex(since, until, dryRun) ++
        fs2.Stream.eval(info"deleting studies from $since to $until") ++
        pullAndDelete(since, until, dryRun)

    def pullAndIndex(since: Instant, until: Instant, dryRun: Boolean = false): fs2.Stream[IO, Unit] =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
      studies
        .find(filter)
        .projection(indexDocProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList)
        .evalTap(_.traverse_(x => debug"received $x"))
        .evalMap(storeBulk(_, dryRun))

    def pullAndDelete(since: Instant, until: Instant, dryRun: Boolean = false): fs2.Stream[IO, Unit] =
      val filter =
        Filter
          .gte("ts", since.asBsonTimestamp)
          .and(Filter.lt("ts", until.asBsonTimestamp))
          .and(Filter.eq("ns", "study.study"))
          .and(Filter.eq("op", "d"))
      oplogs
        .find(filter)
        .projection(deleteDocProjection)
        .boundedStream(config.batchSize)
        .chunkN(config.batchSize)
        .map(_.toList.flatMap(extractId))
        .evalTap(xs => info"Deleting $xs")
        .evalMap:
          dryRun.fold(
            xs => xs.traverse_(x => debug"Would delete $x"),
            elastic.deleteMany(index, _)
          )

    def storeBulk(docs: List[Document], dryRun: Boolean = false): IO[Unit] =
      info"Received ${docs.size} studies to index" *>
        docs.toSources.flatMap: sources =>
          dryRun.fold(
            sources.traverse_(source => debug"Would index $source"),
            elastic.storeBulk(index, sources) *> info"Indexed ${sources.size} studies"
              .handleErrorWith: e =>
                Logger[IO].error(e)(s"Failed to index studies: ${docs.map(_.id).mkString(", ")}")
              .whenA(docs.nonEmpty)
          )

    def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    def extractId(doc: Document): Option[Id] =
      doc.getNestedAs[String](F.oplogId).map(Id.apply)

    def intervalStream: fs2.Stream[IO, (Instant, Instant)] =
      fs2.Stream
        .eval:
          config.startAt
            .fold(store.get(index.value))(Instant.ofEpochSecond(_).some.pure[IO])
        .flatMap: startAt =>
          startAt.fold(fs2.Stream.empty)(since => fs2.Stream(since))
            ++ fs2.Stream
              .eval(IO.realTimeInstant)
              .flatMap(now => fs2.Stream.unfold(now)(s => (s, s.plusSeconds(config.interval.toSeconds)).some))
        .zipWithNext
        .map((since, until) => since -> until.get)

    extension (docs: List[Document])
      private def toSources: IO[List[StudySourceWithId]] =
        val studyIds = docs.flatMap(_.id).distinct // TODO do We need distict here?
        chapters
          .byStudyIds(studyIds)
          .flatMap: chapters =>
            docs
              .traverseFilter(_.toSource(chapters))

    // TODO log reasons
    type StudySourceWithId = (String, StudySource)
    extension (doc: Document)
      private def toSource(chapters: Map[String, StudyData]): IO[Option[StudySourceWithId]] =
        doc.id
          .flatMap: id =>
            (
              doc.getName,
              doc.getOwnerId,
              doc.getMembers.some,
              doc.getChapterNames(chapters),
              doc.getChapterTexts(chapters),
              doc.getLikes.some,
              doc.getPublic.some,
              doc.getTopics.some
            )
              .mapN(StudySource.apply)
              .map(id -> _)
          .pure[IO]
          .flatTap: source =>
            def reason =
              doc.id.fold("missing doc._id; ")(_ => "")
                + doc.getName.fold("missing doc.name; ")(_ => "")
                + doc.getOwnerId.fold("missing doc.ownerId; ")(_ => "")
                + doc.getChapterNames(chapters).fold("missing doc.chapterNames; ")(_ => "")
                + doc.getChapterTexts(chapters).fold("missing doc.chapterTexts; ")(_ => "")
            info"failed to convert document to source: $doc because $reason".whenA(source.isEmpty)

      private def getName    = doc.getString(F.name)
      private def getOwnerId = doc.getString(F.ownerId)
      private def getMembers = doc.getDocument(F.members).fold(Nil)(_.toMap.keys.toList)
      private def getTopics  = doc.getList(F.topics).map(_.flatMap(_.asString)).getOrElse(Nil)
      private def getLikes   = doc.getInt(F.likes).getOrElse(0)
      private def getChapterTexts(chapters: Map[String, StudyData]) =
        chapters.get(doc.id.getOrElse("")).map(_.chapterTexts)
      private def getChapterNames(chapters: Map[String, StudyData]) =
        chapters.get(doc.id.getOrElse("")).map(_.chapterNames)
      private def getPublic = doc.getString(F.visibility).map(_ == "public").getOrElse(true)

  object F:
    val name       = "name"
    val likes      = "likes"
    val members    = "members"
    val ownerId    = "ownerId"
    val visibility = "visibility"
    val topics     = "topics"
    val createdAt  = "createdAt"
    val updatedAt  = "updatedAt"
    val oplogId    = "o._id"
