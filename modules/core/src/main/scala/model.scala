package lila.search

import com.sksamuel.elastic4s.requests.searches.SearchResponse as ESR
import lila.search.game.{ Clocking, Sorting }
import org.joda.time.DateTime

case class Id(value: String)

case class StringQuery(value: String)
case class From(value: Int)
case class Size(value: Int)

case class SearchResponse(hitIds: List[String])

object SearchResponse:

  def apply(res: ESR): SearchResponse =
    SearchResponse(res.hits.hits.toList.map(_.id))

case class CountResponse(count: Int)

object CountResponse:

  def apply(res: ESR): CountResponse =
    CountResponse(res.totalHits.toInt)

case class Team(text: String)

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
)

case class Forum(text: String, troll: Boolean)

case class Study(text: String, userId: Option[String])
