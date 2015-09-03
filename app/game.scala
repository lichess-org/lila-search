package lila.search
package game

import chess.{ Mode, Status, Openings }
import org.joda.time.DateTime

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._

object Fields {
  val status = "s"
  val turns = "t"
  val rated = "r"
  val perf = "p"
  val uids = "u"
  val winner = "w"
  val winnerColor = "c"
  val averageRating = "a"
  val ai = "i"
  val opening = "o"
  val date = "d"
  val duration = "l"
  val analysed = "n"
  val whiteUser = "wu"
  val blackUser = "bu"
}

object Mapping {
  import Fields._
  def fields = Seq(
    status typed ShortType,
    turns typed ShortType,
    rated typed BooleanType,
    perf typed ShortType,
    uids typed StringType,
    winner typed StringType,
    winnerColor typed ShortType,
    averageRating typed ShortType,
    ai typed ShortType,
    opening typed StringType,
    date typed DateType format Date.format,
    duration typed IntegerType,
    analysed typed BooleanType,
    whiteUser typed StringType,
    blackUser typed StringType
  ).map(_ index "not_analyzed")
}

case class Query(
    user1: Option[String] = None,
    user2: Option[String] = None,
    winner: Option[String] = None,
    winnerColor: Option[Int] = None,
    perf: Option[Int] = None,
    status: Option[Int] = None,
    turns: Range[Int] = Range.none,
    averageRating: Range[Int] = Range.none,
    hasAi: Option[Boolean] = None,
    aiLevel: Range[Int] = Range.none,
    rated: Option[Boolean] = None,
    opening: Option[String] = None,
    date: Range[DateTime] = Range.none,
    duration: Range[Int] = Range.none,
    sorting: Sorting = Sorting.default,
    analysed: Option[Boolean] = None,
    whiteUser: Option[String] = None,
    blackUser: Option[String] = None) extends lila.search.Query {

  import Fields._

  def searchDef(from: From, size: Size) = index =>
    search in index.withType query makeQuery sort sorting.definition start from.value size size.value

  def countDef = index => count from index.withType query makeQuery

  private lazy val makeQuery = filteredQuery query matchall filter {
    List(
      usernames map { termFilter(Fields.uids, _) },
      toFilters(winner, Fields.winner),
      toFilters(winnerColor, Fields.winnerColor),
      turns filters Fields.turns,
      averageRating filters Fields.averageRating,
      duration map (60 *) filters Fields.duration,
      date map Date.formatter.print filters Fields.date,
      hasAiFilters,
      (hasAi | true).fold(aiLevel filters Fields.ai, Nil),
      toFilters(perf, Fields.perf),
      toFilters(rated, Fields.rated),
      toFilters(opening, Fields.opening),
      toFilters(status, Fields.status),
      toFilters(analysed, Fields.analysed),
      toFilters(whiteUser, Fields.whiteUser),
      toFilters(blackUser, Fields.blackUser)
    ).flatten match {
        case Nil     => matchAllFilter
        case filters => must(filters: _*)
      }
  }

  def usernames = List(user1, user2).flatten

  private def hasAiFilters = hasAi.toList map { a =>
    a.fold(existsFilter(Fields.ai), missingFilter(Fields.ai))
  }

  private def toFilters(query: Option[_], name: String) = query.toList map {
    case s: String => termFilter(name, s.toLowerCase)
    case x         => termFilter(name, x)
  }
}

object Query {

  import play.api.libs.json._

  import Range.rangeJsonReader
  private implicit val sortingJsonReader = play.api.libs.json.Json.reads[Sorting]
  implicit val dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)
  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}

case class Sorting(f: String, order: String) {
  import org.elasticsearch.search.sort.SortOrder
  def definition =
    field sort (Sorting.fieldKeys contains f).fold(f, Sorting.default.f) order
      (order.toLowerCase == "asc").fold(SortOrder.ASC, SortOrder.DESC)
}
object Sorting {

  val default = Sorting(Fields.date, "desc")

  val fieldKeys = List(Fields.date, Fields.turns, Fields.averageRating)
}
