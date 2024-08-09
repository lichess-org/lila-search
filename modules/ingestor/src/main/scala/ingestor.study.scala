package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import lila.search.spec.StudySource
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.Projection
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

import java.time.Instant

trait StudyIngestor:
  // pull changes from study MongoDB and ingest into elastic search
  def watch: fs2.Stream[IO, Unit]

object StudyIngestor:

  private val index = Index.Study

  private val interestedfields = List("_id", F.name, F.members, F.ownerId, F.visibility, F.topics)

  private val docProjector = Projection.include(interestedfields)

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
    def watch: fs2.Stream[IO, Unit] =
      startStream
        .meteredStartImmediately(config.interval)
        .flatMap: (since, until) =>
          run(since, until)

    def run(since: Instant, until: Instant): fs2.Stream[IO, Unit] =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
      fs2.Stream
        .eval(IO.println(s"Indexing studies from $since to $until")) >>
        studies
          .find(filter)
          .projection(docProjector)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList)
          // .metered(1.second) // to avoid overloading the elasticsearch
          .evalMap(docs => storeBulk(docs) *> saveLastIndexedTimestamp(until))

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

    def startAt: IO[Instant] =
      config.startAt
        .fold(store.get(index.value))(Instant.ofEpochSecond(_).some.pure[IO])
        .flatMap(_.fold(IO.realTimeInstant)(IO.pure))

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
        val studyIds = docs.flatMap(_.id).distinct
        chapters
          .byStudyIds(studyIds)
          .flatMap: chapters =>
            docs
              .traverse(_.toSource(chapters))
              .map(_.flatten)

    type StudySourceWithId = (String, StudySource)
    extension (doc: Document)
      private def toSource(chapters: Map[String, StudyData]): IO[Option[StudySourceWithId]] =
        doc.id
          .flatMap: id =>
            val name         = doc.getString(F.name)
            val ownerId      = doc.getString(F.ownerId)
            val members      = doc.getList(F.members).map(_.flatMap(_.asString)).getOrElse(Nil)
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
