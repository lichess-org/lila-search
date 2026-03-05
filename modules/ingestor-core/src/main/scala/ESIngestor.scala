package lila.search
package ingestor

import cats.effect.IO
import cats.mtl.Handle.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import org.typelevel.log4cats.{ Logger, LoggerFactory }

class ESIngestor[A: Indexable: HasStringId](index: Index, elastic: ESClient[IO])(using LoggerFactory[IO])
    extends Ingestor[A]:

  private given Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")

  def ingest(stream: fs2.Stream[IO, Repo.Result[A]]): IO[Unit] =
    stream
      .evalMap: result =>
        storeBulk(result.toIndex) *>
          updateBulk(result.toUpdate) *>
          deleteMany(result.toDelete)
      .compile
      .drain

  private def storeBulk(sources: List[A]): IO[Unit] =
    Logger[IO].info(s"Indexing ${sources.size} docs to ${index.value}") *>
      allow:
        elastic.storeBulk(index, sources)
      .rescue: e =>
        Logger[IO].error(e.asException)(
          s"Failed to ${index.value} index: ${sources.map(_.id).mkString(", ")}"
        )
          *> IO.raiseError(e.asException)
      .whenA(sources.nonEmpty)

  private def updateBulk(sources: List[(Id, Map[String, Any])]): IO[Unit] =
    Logger[IO].info(s"Updating ${sources.size} docs to ${index.value}") *>
      allow:
        elastic.updateBulk(index, sources)
      .rescue: e =>
        Logger[IO].error(e.asException)(
          s"Failed to ${index.value} index: ${sources.map(_._1).mkString(", ")}"
        )
          *> IO.raiseError(e.asException)
      .whenA(sources.nonEmpty) *>
      Logger[IO].info(s"Updated ${sources.size} ${index.value}s")

  private def deleteMany(ids: List[Id]): IO[Unit] =
    allow:
      elastic.deleteMany(index, ids)
    .rescue: e =>
      Logger[IO].error(e.asException)(
        s"Failed to delete ${index.value}: ${ids.map(_.value).mkString(", ")}"
      )
        *> IO.raiseError(e.asException)
    .flatTap(_ => Logger[IO].info(s"Deleted ${ids.size} ${index.value}s"))
      .whenA(ids.nonEmpty)
