package lila.search
package game

import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
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
)

object Fields:
  val status        = "s"
  val turns         = "t"
  val rated         = "r"
  val perf          = "p"
  val uids          = "u"
  val winner        = "w"
  val loser         = "o"
  val winnerColor   = "c"
  val averageRating = "a"
  val ai            = "i"
  val date          = "d"
  val duration      = "l"
  val clockInit     = "ct"
  val clockInc      = "ci"
  val analysed      = "n"
  val whiteUser     = "wu"
  val blackUser     = "bu"
  val source        = "so"

object Mapping:
  import Fields.*
  def fields =
    Seq( // only keep docValues for sortable fields
      keywordField(status).copy(docValues = Some(false)),
      shortField(turns).copy(docValues = Some(true)),
      booleanField(rated).copy(docValues = Some(false)),
      keywordField(perf).copy(docValues = Some(false)),
      keywordField(uids).copy(docValues = Some(false)),
      keywordField(winner).copy(docValues = Some(false)),
      keywordField(loser).copy(docValues = Some(false)),
      keywordField(winnerColor).copy(docValues = Some(false)),
      shortField(averageRating).copy(docValues = Some(true)),
      shortField(ai).copy(docValues = Some(false)),
      dateField(date).copy(format = Some(SearchDateTime.format), docValues = Some(true)),
      intField(duration).copy(docValues = Some(false)),
      intField(clockInit).copy(docValues = Some(false)),
      shortField(clockInc).copy(docValues = Some(false)),
      booleanField(analysed).copy(docValues = Some(false)),
      keywordField(whiteUser).copy(docValues = Some(false)),
      keywordField(blackUser).copy(docValues = Some(false)),
      keywordField(source).copy(docValues = Some(false))
    )

object GameQuery:
  given query: Queryable[Game] = new:

    val timeout = 5.seconds
    val index   = "game"

    def searchDef(query: Game)(from: SearchFrom, size: SearchSize) =
      search(index)
        .query(makeQuery(query))
        .fetchSource(false)
        .sortBy(query.sorting.definition)
        .start(from.value)
        .size(size.value)
        .timeout(timeout)

    def countDef(query: Game) = count(index).query(makeQuery(query))

    private def makeQuery(query: Game): Query =

      import query.*
      def usernames = List(user1, user2).flatten

      def hasAiQueries =
        hasAi.toList.map: a =>
          a.fold(existsQuery(Fields.ai), not(existsQuery(Fields.ai)))

      def toQueries(query: Option[?], name: String): List[TermQuery] =
        query.toList.map:
          case s: String => termQuery(name, s.toLowerCase)
          case x         => termQuery(name, x)

      List(
        usernames.map(termQuery(Fields.uids, _)),
        toQueries(winner, Fields.winner),
        toQueries(loser, Fields.loser),
        toQueries(winnerColor, Fields.winnerColor),
        turns.queries(Fields.turns),
        averageRating.queries(Fields.averageRating),
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

case class Sorting(f: String, order: String):
  import com.sksamuel.elastic4s.requests.searches.sort.SortOrder
  def definition =
    fieldSort(Sorting.fieldKeys.contains(f).fold(f, Sorting.default.f))
      .order((order.toLowerCase == "asc").fold(SortOrder.ASC, SortOrder.DESC))

object Sorting:

  val default = Sorting(Fields.date, "desc")

  val fieldKeys = List(Fields.date, Fields.turns, Fields.averageRating)
