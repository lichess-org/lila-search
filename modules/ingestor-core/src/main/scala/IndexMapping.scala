package lila.search
package ingestor

import com.sksamuel.elastic4s.Indexable

trait IndexMapping:
  type Out
  def repo: Repo[Out]
  given indexable: Indexable[Out]

  def withRepo[R](f: Repo[Out] => (Indexable[Out] ?=> R)): R = f(repo)

object Registry:

  import smithy4s.json.Json.given
  import smithy4s.schema.Schema
  import com.github.plokhotnyuk.jsoniter_scala.core.*

  given [A] => Schema[A] => Indexable[A] = a => writeToString(a)
  given Indexable[DbGame] = a => writeToString(Translate.game(a))
  given Indexable[DbForum] = a => writeToString(Translate.forum(a))
  given Indexable[DbUblog] = a => writeToString(Translate.ublog(a))
  given Indexable[(DbStudy, StudyChapterData)] = a => writeToString(Translate.study.tupled(a))
  given Indexable[DbTeam] = a => writeToString(Translate.team(a))

  type Of[I <: Index] = I match
    case Index.Game.type => DbGame
    case Index.Forum.type => DbForum
    case Index.Ublog.type => DbUblog
    case Index.Study.type => (DbStudy, StudyChapterData)
    case Index.Team.type => DbTeam

class Registry(
    game: Repo[DbGame],
    forum: Repo[DbForum],
    ublog: Repo[DbUblog],
    study: Repo[(DbStudy, StudyChapterData)],
    team: Repo[DbTeam]
):
  import com.sksamuel.elastic4s.Indexable
  import Registry.{ Of, given }

  /** Helper to create an IndexMapping from a repo */
  private def makeMapping[A: Indexable](r: Repo[A]): IndexMapping =
    new IndexMapping:
      type Out = A
      def repo = r
      def indexable = summon[Indexable[A]]

  def apply(index: Index): IndexMapping = index match
    case Index.Game => makeMapping(game)
    case Index.Forum => makeMapping(forum)
    case Index.Ublog => makeMapping(ublog)
    case Index.Study => makeMapping(study)
    case Index.Team => makeMapping(team)

  /** Get a specific repo when the index is statically known */
  def get[I <: Index](using ev: ValueOf[I]): Repo[Of[I]] =
    apply(ev.value).repo.asInstanceOf[Repo[Of[I]]]
