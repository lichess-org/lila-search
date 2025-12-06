package lila.search
package ingestor

import cats.effect.IO

object IndexRegistry:

  import smithy4s.json.Json.given
  import smithy4s.schema.Schema
  import com.github.plokhotnyuk.jsoniter_scala.core.*
  import com.sksamuel.elastic4s.Indexable

  given [A] => Schema[A] => Indexable[A] = a => writeToString(a)
  given Indexable[DbGame] = a => writeToString(Translate.game(a))
  given Indexable[DbForum] = a => writeToString(Translate.forum(a))
  given Indexable[DbUblog] = a => writeToString(Translate.ublog(a))
  given Indexable[DbStudy] = a => writeToString(Translate.study2(a))
  given Indexable[DbTeam] = a => writeToString(Translate.team(a))

  given HasStringId[DbGame]:
    extension (a: DbGame) def id: String = a.id
  given HasStringId[DbForum]:
    extension (a: DbForum) def id: String = a.id
  given HasStringId[DbUblog]:
    extension (a: DbUblog) def id: String = a.id
  given HasStringId[(DbStudy, StudyChapterData)]:
    extension (a: (DbStudy, StudyChapterData)) def id: String = a._1.id
  given HasStringId[DbStudy]:
    extension (a: DbStudy) def id: String = a.id
  given HasStringId[DbTeam]:
    extension (a: DbTeam) def id: String = a.id

  type Of[I <: Index] = I match
    case Index.Game.type => DbGame
    case Index.Forum.type => DbForum
    case Index.Ublog.type => DbUblog
    case Index.Study.type => (DbStudy, StudyChapterData)
    case Index.Study2.type => DbStudy
    case Index.Team.type => DbTeam

  trait IndexMapping:
    type Out
    def repo: IO[Repo[Out]]
    given indexable: Indexable[Out]
    given hasId: HasStringId[Out]
    def withRepo[R](f: Repo[Out] => (Indexable[Out] ?=> HasStringId[Out] ?=> IO[R])): IO[R] =
      repo.flatMap(f(_))

class IndexRegistry(
    game: IO[Repo[DbGame]],
    forum: IO[Repo[DbForum]],
    ublog: IO[Repo[DbUblog]],
    study: IO[Repo[DbStudy]],
    study2: IO[Repo[DbStudy]],
    team: IO[Repo[DbTeam]]
):
  import com.sksamuel.elastic4s.Indexable
  import IndexRegistry.{ IndexMapping, Of, given }

  /**
   * Helper to create an IndexMapping from a repo
   */
  private def makeMapping[A: Indexable: HasStringId](r: IO[Repo[A]]): IndexMapping =
    new IndexMapping:
      type Out = A
      def repo = r
      def indexable = summon[Indexable[A]]
      def hasId = summon[HasStringId[A]]

  def apply(index: Index): IndexMapping = index match
    case Index.Game => makeMapping[DbGame](game)
    case Index.Forum => makeMapping[DbForum](forum)
    case Index.Ublog => makeMapping[DbUblog](ublog)
    case Index.Study => makeMapping[DbStudy](study)
    case Index.Study2 => makeMapping[DbStudy](study2)
    case Index.Team => makeMapping[DbTeam](team)

  /**
   * Get a specific repo when the index is statically known
   */
  def get[I <: Index](using ev: ValueOf[I]): IO[Repo[Of[I]]] =
    apply(ev.value).repo.asInstanceOf[IO[Repo[Of[I]]]]
