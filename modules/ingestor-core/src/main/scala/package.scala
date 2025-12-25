package lila.search
package ingestor

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import org.typelevel.log4cats.Logger

import java.time.Instant

extension (index: Index)

  def ingestValue: String =
    index.value

  def updateElastic[A: Indexable: HasStringId](
      result: Repo.Result[A]
  )(using Logger[IO], ESClient[IO], KVStore): IO[Unit] =
    index.storeBulk(result.toIndex) *>
      index.updateBulk(result.toUpdate) *>
      index.deleteMany(result.toDelete) *>
      result.timestamp.traverse_(index.saveTimestamp)

  private def storeBulk[A: Indexable: HasStringId](
      sources: List[A]
  )(using logger: Logger[IO], elastic: ESClient[IO]): IO[Unit] =
    Logger[IO].info(s"Indexing ${sources.size} docs to ${index.ingestValue}") *>
      allow:
        elastic.storeBulk(index, sources)
      .rescue: e =>
        logger.error(e.asException)(
          s"Failed to ${index.ingestValue} index: ${sources.map(_.id).mkString(", ")}"
        )
          *> IO.raiseError(e.asException)
      .whenA(sources.nonEmpty)

  private def updateBulk(
      sources: List[(Id, Map[String, Any])]
  )(using logger: Logger[IO], elastic: ESClient[IO]): IO[Unit] =
    Logger[IO].info(s"Updating ${sources.size} docs to ${index.ingestValue}") *>
      allow:
        elastic.updateBulk(index, sources)
      .rescue: e =>
        logger.error(e.asException)(
          s"Failed to ${index.ingestValue} index: ${sources.map(_._1).mkString(", ")}"
        )
          *> IO.raiseError(e.asException)
      .whenA(sources.nonEmpty) *>
      logger.info(s"Updated ${sources.size} ${index.ingestValue}s")

  private def deleteMany(ids: List[Id])(using logger: Logger[IO], elastic: ESClient[IO]): IO[Unit] =
    allow:
      elastic.deleteMany(index, ids)
    .rescue: e =>
      logger.error(e.asException)(
        s"Failed to delete ${index.ingestValue}: ${ids.map(_.value).mkString(", ")}"
      )
        *> IO.raiseError(e.asException)
    .flatTap(_ => Logger[IO].info(s"Deleted ${ids.size} ${index.ingestValue}s"))
      .whenA(ids.nonEmpty)

  private def saveTimestamp(time: Instant)(using logger: Logger[IO], store: KVStore): IO[Unit] =
    store.put(index.ingestValue, time) *>
      Logger[IO].info(s"Stored last indexed time ${time.getEpochSecond} for ${index.ingestValue}")
