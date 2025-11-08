package lila.search
package ingestor

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import org.typelevel.log4cats.Logger

object ElasticSink:

  def updateElastic[A: Indexable: HasStringId](
      index: Index,
      elastic: ESClient[IO],
      store: KVStore
  )(result: Repo.Result[A])(using logger: Logger[IO]): IO[Unit] =
    storeBulk(index, elastic, result.toIndex) *>
      deleteMany(index, elastic, result.toDelete)
      *> result.timestamp.traverse_(time =>
        store.put(index.value, time) *>
          Logger[IO].info(s"Stored last indexed time ${time.getEpochSecond} for ${index.value}")
      )

  private def deleteMany(
      index: Index,
      elastic: ESClient[IO],
      ids: List[Id]
  )(using logger: Logger[IO]): IO[Unit] =
    allow:
      elastic.deleteMany(index, ids)
    .rescue: e =>
      logger.error(e.asException)(s"Failed to delete ${index.value}: ${ids.map(_.value).mkString(", ")}")
    .flatTap(_ => Logger[IO].info(s"Deleted ${ids.size} ${index.value}s"))
      .whenA(ids.nonEmpty)

  private def storeBulk[A: Indexable: HasStringId](
      index: Index,
      elastic: ESClient[IO],
      sources: List[A]
  )(using logger: Logger[IO]): IO[Unit] =
    Logger[IO].info(s"Received ${sources.size} docs to ${index.value}") *>
      allow:
        elastic.storeBulk(index, sources)
      .rescue: e =>
        logger.error(e.asException)(s"Failed to ${index.value} index: ${sources.map(_.id).mkString(", ")}")
      .whenA(sources.nonEmpty) *>
      logger.info(s"Indexed ${sources.size} ${index.value}s")
