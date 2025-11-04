package lila.search
package ingestor

import cats.effect.IO
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

// Generic exporter abstraction - fetch from Repo, transform, write to sink
trait Exporter:
  def run(since: Instant, until: Instant): IO[Unit]

object Exporter:

  // Factory for total transformations: B => A
  def apply[A, B](
      repo: Repo[B],
      transform: (String, B) => A,
      sink: fs2.Pipe[IO, (String, A), Unit]
  )(using
      lf: LoggerFactory[IO]
  ): Exporter = new:
    given logger: Logger[IO] = lf.getLogger

    def run(since: Instant, until: Instant): IO[Unit] =
      info"Starting export from $since to $until" *>
        repo
          .fetch(since, until)
          .flatMap: result =>
            fs2.Stream.emits(result.toIndex)
          .map { case (id, dbModel) =>
            (id, transform(id, dbModel))
          }
          .through(sink)
          .compile
          .drain
        *> info"Export completed"

  // Factory for partial transformations: B => Option[A]
  def applyPartial[A, B](
      repo: Repo[B],
      transform: (String, B) => Option[A],
      sink: fs2.Pipe[IO, (String, A), Unit]
  )(using
      lf: LoggerFactory[IO]
  ): Exporter = new:
    given logger: Logger[IO] = lf.getLogger

    def run(since: Instant, until: Instant): IO[Unit] =
      info"Starting export from $since to $until" *>
        repo
          .fetch(since, until)
          .flatMap: result =>
            fs2.Stream.emits(result.toIndex)
          .flatMap:
            case (id, dbModel) =>
              transform(id, dbModel).map(a => fs2.Stream.emit((id, a))).getOrElse(fs2.Stream.empty)
          .through(sink)
          .compile
          .drain
        *> info"Export completed"

// Elasticsearch sink for batch indexing
// Note: For production use with watch mode, state management, and sophisticated error handling, use Ingestor.
object ElasticsearchSink:

  def apply[A](
      elastic: ESClient[IO],
      index: Index,
      batchSize: Int = 100,
      dry: Boolean = false
  )(using
      schema: smithy4s.schema.Schema[A],
      lf: LoggerFactory[IO]
  ): fs2.Pipe[IO, (String, A), Unit] =
    import cats.mtl.Handle.*
    import cats.syntax.all.*
    import com.github.plokhotnyuk.jsoniter_scala.core.*
    import com.sksamuel.elastic4s.Indexable
    import smithy4s.json.Json.given

    given logger: Logger[IO] = lf.getLogger
    given [B] => smithy4s.schema.Schema[B] => Indexable[B] = b => writeToString(b)

    stream =>
      stream
        .chunkN(batchSize)
        .evalMap: chunk =>
          val sources = chunk.toList
          if dry then info"Would index ${sources.size} documents to ${index.value}"
          else
            info"Indexing ${sources.size} documents to ${index.value}" *>
              allow:
                elastic.storeBulk(index, sources)
              .rescue: e =>
                logger.error(e.asException)(s"Failed to index batch to ${index.value}") *>
                  e.asException.raiseError[IO, Unit]
