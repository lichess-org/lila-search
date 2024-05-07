package lila.search

import org.joda.time.DateTime
import lila.search.game.Clocking
import lila.search.game.Sorting

sealed abstract class Query

object Query {
  implicit val query: Queryable[Query] =
    new Queryable[Query] {
      def searchDef(query: Query)(from: From, size: Size) =
        query match {
          case q: Game  => game.GameQuery.query.searchDef(q)(from, size)
          case q: Study => study.StudyQuery.query.searchDef(q)(from, size)
          case q: Forum => forum.ForumQuery.query.searchDef(q)(from, size)
          case q: Team  => team.TeamQuery.query.searchDef(q)(from, size)
        }

      def countDef(query: Query) =
        query match {
          case q: Game  => game.GameQuery.query.countDef(q)
          case q: Study => study.StudyQuery.query.countDef(q)
          case q: Forum => forum.ForumQuery.query.countDef(q)
          case q: Team  => team.TeamQuery.query.countDef(q)
        }
    }
}

case class Team(text: String) extends Query

case class Game(
    user1: Option[String] = None,
    user2: Option[String] = None,
    winner: Option[String] = None,
    loser: Option[String] = None,
    winnerColor: Option[Int] = None,
    perf: List[Int] = List.empty,
    source: Option[Int] = None,
    status: Option[Int] = None,
    turns: Range[Int] = Range.none,
    averageRating: Range[Int] = Range.none,
    hasAi: Option[Boolean] = None,
    aiLevel: Range[Int] = Range.none,
    rated: Option[Boolean] = None,
    date: Range[DateTime] = Range.none,
    duration: Range[Int] = Range.none,
    clock: Clocking = Clocking(),
    sorting: Sorting = Sorting.default,
    analysed: Option[Boolean] = None,
    whiteUser: Option[String] = None,
    blackUser: Option[String] = None
) extends Query

case class Forum(text: String, troll: Boolean) extends Query

case class Study(text: String, userId: Option[String]) extends Query
