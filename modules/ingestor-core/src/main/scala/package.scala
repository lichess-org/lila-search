package lila.search
package ingestor

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sksamuel.elastic4s.Indexable
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import smithy4s.json.Json.given
import smithy4s.schema.Schema

given [A] => Schema[A] => Indexable[A] = a => writeToString(a)
def gameIndexable(botIds: Set[String]): Indexable[DbGame] = a => writeToString(Translate.game(a, botIds))
given Indexable[DbForum] = a => writeToString(Translate.forum(a))
given Indexable[DbUblog] = a => writeToString(Translate.ublog(a))
given Indexable[(DbStudy, Option[List[StudyChapterData]])] = (study, chapters) =>
  writeToString(Translate.study(study, chapters))
given Indexable[DbTeam] = a => writeToString(Translate.team(a))

given HasStringId[DbGame]:
  extension (a: DbGame) def id: String = a.id
given HasStringId[DbForum]:
  extension (a: DbForum) def id: String = a.id
given HasStringId[DbUblog]:
  extension (a: DbUblog) def id: String = a.id
given HasStringId[(DbStudy, Option[List[StudyChapterData]])]:
  extension (a: (DbStudy, Option[List[StudyChapterData]])) def id: String = a._1.id
given HasStringId[DbTeam]:
  extension (a: DbTeam) def id: String = a.id

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
