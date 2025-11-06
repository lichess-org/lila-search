package lila.search
package ingestor

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sksamuel.elastic4s.Indexable
import org.typelevel.log4cats.Logger
import smithy4s.json.Json.given
import smithy4s.schema.Schema

object ElasticSink:

  given [A] => Schema[A] => Indexable[A] = a => writeToString(a)

  given Indexable[DbGame] = a => writeToString(Translate.game(a))
  given Indexable[DbForum] = a => writeToString(Translate.forum(a))
  given Indexable[DbUblog] = a => writeToString(Translate.ublog(a))
  given Indexable[(DbStudy, StudyChapterData)] = a => writeToString(Translate.study.tupled(a))
  given Indexable[DbTeam] = a => writeToString(Translate.team(a))

  def updateElastic[A: Indexable](
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

  private def storeBulk[A: Indexable](
      index: Index,
      elastic: ESClient[IO],
      sources: List[SourceWithId[A]]
  )(using logger: Logger[IO]): IO[Unit] =
    Logger[IO].info(s"Received ${sources.size} docs to ${index.value}") *>
      allow:
        elastic.storeBulk(index, sources)
      .rescue: e =>
        logger.error(e.asException)(s"Failed to ${index.value} index: ${sources.map(_.id).mkString(", ")}")
      .whenA(sources.nonEmpty) *>
      logger.info(s"Indexed ${sources.size} ${index.value}s")
