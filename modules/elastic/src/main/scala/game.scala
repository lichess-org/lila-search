package lila.search
package game

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.requests.searches.sort.FieldSort
import com.sksamuel.elastic4s.requests.searches.term.TermQuery

import java.time.Instant
import scala.concurrent.duration.*

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
    date: Range[Instant] = Range.none,
    duration: Range[Int] = Range.none,
    sorting: Sorting = Sorting.default,
    analysed: Option[Boolean] = None,
    whiteUser: Option[String] = None,
    blackUser: Option[String] = None,
    clockInit: Option[Int] = None,
    clockInc: Option[Int] = None
):

  val timeout = 5.seconds

  def searchDef(from: From, size: Size) =
    search(Game.index)
      .query(makeQuery)
      .fetchSource(false)
      .sortBy(sorting.definition)
      .start(from.value)
      .size(size.value)
      .timeout(timeout)

  def countDef = count(Game.index).query(makeQuery)

  private def makeQuery: Query =

    // user1 alone: games where user1 played as white or black
    // user1 + user2: games between user1 and user2
    def userQueries: List[Query] =
      (user1, user2) match
        case (Some(u1), Some(u2)) =>
          List(
            boolQuery().should(
              boolQuery().must(termQuery(Fields.whiteUser, u1), termQuery(Fields.blackUser, u2)),
              boolQuery().must(termQuery(Fields.whiteUser, u2), termQuery(Fields.blackUser, u1))
            )
          )
        case _ =>
          List(user1, user2).flatten.map: u =>
            boolQuery().should(termQuery(Fields.whiteUser, u), termQuery(Fields.blackUser, u))

    // winner W: games where W played as white and white won, or W played as black and black won
    def winnerQueries: List[Query] =
      winner.toList.map: w =>
        boolQuery().should(
          boolQuery().must(termQuery(Fields.whiteUser, w), termQuery(Fields.winnerColor, 1)),
          boolQuery().must(termQuery(Fields.blackUser, w), termQuery(Fields.winnerColor, 2))
        )

    // loser L: games where L played as white and black won, or L played as black and white won
    def loserQueries: List[Query] =
      loser.toList.map: l =>
        boolQuery().should(
          boolQuery().must(termQuery(Fields.whiteUser, l), termQuery(Fields.winnerColor, 2)),
          boolQuery().must(termQuery(Fields.blackUser, l), termQuery(Fields.winnerColor, 1))
        )

    def hasAiQueries =
      hasAi.toList.map: a =>
        if a then rangeQuery(Fields.ai).gt(0)
        else termQuery(Fields.ai, 0)

    // averageRating is 0 when either rating is missing; exclude 0s from range queries
    def avgRatingQueries =
      if averageRating.nonEmpty then
        rangeQuery(Fields.averageRating).gt(0) :: averageRating.queries(Fields.averageRating)
      else Nil

    def toQueries(query: Option[String | Int | Boolean], name: String): List[TermQuery] =
      query.toList.map:
        case s: String => termQuery(name, s.toLowerCase)
        case x => termQuery(name, x)

    List(
      userQueries,
      winnerQueries,
      loserQueries,
      toQueries(winnerColor, Fields.winnerColor),
      turns.queries(Fields.turns),
      avgRatingQueries,
      duration.queries(Fields.duration),
      clockInit.map(termsQuery(Fields.clockInit, _)).toList,
      clockInc.map(termsQuery(Fields.clockInc, _)).toList,
      date.map(SearchDateTime.fromInstant).queries(Fields.date),
      hasAiQueries,
      hasAi.getOrElse(true).fold(aiLevel.queries(Fields.ai), Nil),
      perf.nonEmpty.fold(List(termsQuery(Fields.perf, perf)), Nil),
      toQueries(source, Fields.source),
      toQueries(rated, Fields.rated),
      toQueries(status, Fields.status),
      toQueries(analysed, Fields.analysed),
      toQueries(whiteUser, Fields.whiteUser),
      toQueries(blackUser, Fields.blackUser)
    ).flatten.compile

object Fields:
  val status = "s"
  val turns = "t"
  val rated = "r"
  val perf = "p"
  val winnerColor = "c"
  val averageRating = "a"
  val ai = "i"
  val date = "d"
  val duration = "l"
  val clockInit = "ct"
  val clockInc = "ci"
  val analysed = "n"
  val whiteUser = "wu"
  val blackUser = "bu"
  val source = "so"
  val whiteRating = "wr"
  val blackRating = "br"
  val chess960Pos = "c9"
  val whiteBot = "wb"
  val blackBot = "bb"

object Mapping:
  def fields = MappingGenerator.generateFields(es.GameSource.schema)

object Game:
  val index = "game2"

case class Sorting(f: String, order: String):
  import com.sksamuel.elastic4s.requests.searches.sort.SortOrder
  def definition: FieldSort =
    fieldSort(Sorting.fieldKeys.contains(f).fold(f, Sorting.default.f))
      .order((order.toLowerCase == "asc").fold(SortOrder.ASC, SortOrder.DESC))

object Sorting:

  val default = Sorting(Fields.date, "desc")
  val fieldKeys = List(Fields.date, Fields.turns, Fields.averageRating)
