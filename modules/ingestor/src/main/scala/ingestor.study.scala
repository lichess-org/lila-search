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
      startStream
        .meteredStartImmediately(config.interval)
        .evalMap: (since, until) =>
          (pullAndIndex(since, until).compile.drain) // ++ pullAndDelete(since, until)).compile.drain
            >> saveLastIndexedTimestamp(until)

    def pullAndIndex(since: Instant, until: Instant): fs2.Stream[IO, Unit] =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
      fs2.Stream
        .eval(info"Indexing studies from $since to $until $filter") >>
        studies
          .find(filter)
          .projection(indexDocProjection)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList)
          // .evalMap(IO.println)
          .evalMap(docs => storeBulk(docs))

    def pullAndDelete(since: Instant, until: Instant): fs2.Stream[IO, Unit] =
      val filter =
        Filter
          .gte("ts", since.asBsonTimestamp)
          .and(Filter.lt("ts", until.asBsonTimestamp))
          .and(Filter.eq("ns", "lichess.study"))
          .and(Filter.eq("op", "d"))
      fs2.Stream
        .eval(info"Deleting studies from $since to $until") >>
        oplogs
          .find(filter)
          .projection(deleteDocProjection)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList.flatMap(extractId))
          .evalTap(xs => info"Deleting $xs")
          .evalMap(elastic.deleteMany(index, _))

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

    def extractId(doc: Document): Option[Id] =
      doc.getNestedAs[String](F.oplogId).map(Id.apply)

    def startStream =
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
        .map: (since, until) =>
          since -> until.get

    extension (docs: List[Document])
      private def toSources: IO[List[StudySourceWithId]] =
        val studyIds = docs.flatMap(_.id).distinct // TODO do We need distict here?
        println("distict test")
        println(studyIds == docs.flatMap(_.id))
        chapters
          .byStudyIds(studyIds)
          .flatMap: chapters =>
            docs
              .traverseFilter(_.toSource(chapters))
              .flatTap(IO.println)

    // TODO log reasons
    type StudySourceWithId = (String, StudySource)
    extension (doc: Document)
      private def toSource(chapters: Map[String, StudyData]): IO[Option[StudySourceWithId]] =
        doc.id
          .flatMap: id =>
            val name         = doc.getString(F.name)
            val ownerId      = doc.getString(F.ownerId)
            val members      = doc.getDocument(F.members).fold(Nil)(_.toMap.keys.toList)
            val topics       = doc.getList(F.topics).map(_.flatMap(_.asString)).getOrElse(Nil)
            val likes        = doc.getInt(F.likes).getOrElse(0)
            val chapterTexts = chapters.get(id).map(_.chapterTexts)
            val chapterNames = chapters.get(id).map(_.chapterNames)
            val public       = doc.getString(F.visibility).map(_ == "public").getOrElse(true)
            (name, ownerId, members.some, chapterNames, chapterTexts, likes.some, public.some, topics.some)
              .mapN(StudySource.apply)
              .map(id -> _)
          .pure[IO]

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
