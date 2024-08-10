package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import mongo4cats.bson.Document
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Filter, Projection }
import org.typelevel.log4cats.Logger

import java.time.Instant

trait StudyDeleter:
  // pull changes study mongodb instance oplog and delete from elastic search
  def watch: fs2.Stream[IO, Unit]

object StudyDeleter:
  private val index = Index.Study

  private val docProjector = Projection.include(F.id)
  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Study)(
      using Logger[IO]
  ): IO[StudyDeleter] =
    mongo.getCollection("oplog.rs").map(apply(elastic, store, config))
  def apply(elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Study)(
      oplogs: MongoCollection
  )(using
      Logger[IO]
  ): StudyDeleter = new:
    def watch: fs2.Stream[IO, Unit] =
      startStream
        .meteredStartImmediately(config.interval)
        .flatMap: (since, until) =>
          run(since, until)

    def run(since: Instant, until: Instant): fs2.Stream[IO, Unit] =
      // val filter =
      //   range("ts")(since, until.some).and(Filter.eq("op", "d")).and(Filter.eq("ns", "lichess.study"))

      val filter =
        range("ts")(since.asBsonTimestamp, until.asBsonTimestamp.some)
          .and(Filter.eq("ns", "lichess.study"))
          .and(Filter.eq("op", "d"))
      println(filter)

      fs2.Stream
        .eval(IO.println(s"Deleting studies from $since to $until")) >>
        oplogs
          .find(filter)
          .projection(docProjector)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList.flatMap(extractId))
          .evalTap(IO.println)
          .evalMap(docs => elastic.deleteMany(index, docs))

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

    def extractId(doc: Document): Option[Id] =
      doc.getNestedAs[String](F.id).map(Id.apply)

  object F:
    val id = "o._id"
