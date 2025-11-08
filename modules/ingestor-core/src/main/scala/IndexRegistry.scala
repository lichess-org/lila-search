package lila.search
package ingestor

object IndexRegistry:

  import smithy4s.json.Json.given
  import smithy4s.schema.Schema
  import com.github.plokhotnyuk.jsoniter_scala.core.*
  import com.sksamuel.elastic4s.Indexable

  given [A] => Schema[A] => Indexable[A] = a => writeToString(a)
  given Indexable[DbGame] = a => writeToString(Translate.game(a))
  given Indexable[DbForum] = a => writeToString(Translate.forum(a))
  given Indexable[DbUblog] = a => writeToString(Translate.ublog(a))
  given Indexable[(DbStudy, StudyChapterData)] = a => writeToString(Translate.study.tupled(a))
  given Indexable[DbTeam] = a => writeToString(Translate.team(a))

  given HasStringId[DbGame]:
    extension (a: DbGame) def id: String = a.id
  given HasStringId[DbForum]:
    extension (a: DbForum) def id: String = a.id
  given HasStringId[DbUblog]:
    extension (a: DbUblog) def id: String = a.id
  given HasStringId[(DbStudy, StudyChapterData)]:
    extension (a: (DbStudy, StudyChapterData)) def id: String = a._1.id
  given HasStringId[DbTeam]:
    extension (a: DbTeam) def id: String = a.id

  type Of[I <: Index] = I match
    case Index.Game.type => DbGame
    case Index.Forum.type => DbForum
    case Index.Ublog.type => DbUblog
    case Index.Study.type => (DbStudy, StudyChapterData)
    case Index.Team.type => DbTeam

  trait IndexMapping:
    type Out
    def repo: Repo[Out]
    given indexable: Indexable[Out]
    given hasId: HasStringId[Out]
    def withRepo[R](f: Repo[Out] => (Indexable[Out] ?=> HasStringId[Out] ?=> R)): R = f(repo)

class IndexRegistry(
    game: Repo[DbGame],
    forum: Repo[DbForum],
    ublog: Repo[DbUblog],
    study: Repo[(DbStudy, StudyChapterData)],
    team: Repo[DbTeam]
):
  import com.sksamuel.elastic4s.Indexable
  import IndexRegistry.{ IndexMapping, Of, given }

  /** Helper to create an IndexMapping from a repo */
  private def makeMapping[A: Indexable: HasStringId](r: Repo[A]): IndexMapping =
    new IndexMapping:
      type Out = A
      def repo = r
      def indexable = summon[Indexable[A]]
      def hasId = summon[HasStringId[A]]

  def apply(index: Index): IndexMapping = index match
    case Index.Game => makeMapping(game)
    case Index.Forum => makeMapping(forum)
    case Index.Ublog => makeMapping(ublog)
    case Index.Study => makeMapping(study)
    case Index.Team => makeMapping(team)

  /** Get a specific repo when the index is statically known */
  def get[I <: Index](using ev: ValueOf[I]): Repo[Of[I]] =
    apply(ev.value).repo.asInstanceOf[Repo[Of[I]]]
