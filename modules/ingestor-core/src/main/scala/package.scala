package lila.search
package ingestor

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import org.typelevel.log4cats.{ Logger, LoggerFactory }

class DryRunIngestor[A](index: Index)(using LoggerFactory[IO]) extends Ingestor[A]:

  private given Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")

  def ingest(stream: fs2.Stream[IO, Repo.Result[A]]): IO[Unit] =
    stream
      .evalMap: result =>
        Logger[IO].info(
          s"[dry] Would upsert ${result.toIndex.size} and delete ${result.toDelete.size} to ${index.value}"
        )
      .compile
      .drain

extension (index: Index)

  def storeBulk[A: Indexable: HasStringId](
      sources: List[A]
  )(using Logger[IO], ESClient[IO]): IO[Unit] =
    Logger[IO].info(s"Indexing ${sources.size} docs to ${index.value}") *>
      allow:
        summon[ESClient[IO]].storeBulk(index, sources)
      .rescue: e =>
        Logger[IO].error(e.asException)(
          s"Failed to ${index.value} index: ${sources.map(_.id).mkString(", ")}"
        )
          *> IO.raiseError(e.asException)
      .whenA(sources.nonEmpty)
