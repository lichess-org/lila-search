package lila.search
package game

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
  val date = "d"
  val duration = "l"
  val analysed = "n"
  val whiteUser = "wu"
  val blackUser = "bu"
  val source = "so"
}

object Mapping {
  import Fields._
  def fields = Seq(
    field(status) typed ShortType,
    field(turns) typed ShortType,
    field(rated) typed BooleanType,
    field(perf) typed ShortType,
    field(uids) typed StringType,
    field(winner) typed StringType,
    field(winnerColor) typed ShortType,
    field(averageRating) typed ShortType,
    field(ai) typed ShortType,
    field(date) typed DateType format Date.format,
    field(duration) typed IntegerType,
    field(analysed) typed BooleanType,
    field(whiteUser) typed StringType,
    field(blackUser) typed StringType,
    field(source) typed ShortType
  ).map(_ index "not_analyzed")
}

case class Query(
    user1: Option[String] = None,
    user2: Option[String] = None,
    winner: Option[String] = None,
    winnerColor: Option[Int] = None,
    perf: Option[Int] = None,
    source: Option[Int] = None,
    status: Option[Int] = None,
    turns: Range[Int] = Range.none,
    averageRating: Range[Int] = Range.none,
    hasAi: Option[Boolean] = None,
    aiLevel: Range[Int] = Range.none,
    rated: Option[Boolean] = None,
    date: Range[DateTime] = Range.none,
    duration: Range[Int] = Range.none,
    sorting: Sorting = Sorting.default,
    analysed: Option[Boolean] = None,
    whiteUser: Option[String] = None,
    blackUser: Option[String] = None) extends lila.search.Query {

  import Fields._

  def searchDef(from: From, size: Size) = index =>
    search in index.toString query makeQuery sort sorting.definition start from.value size size.value

  def countDef = index => search in index.toString query makeQuery size 0

  private lazy val makeQuery = bool {
    must(List(
      usernames map { termQuery(Fields.uids, _) },
      toQueries(winner, Fields.winner),
      toQueries(winnerColor, Fields.winnerColor),
      turns queries Fields.turns,
      averageRating queries Fields.averageRating,
      duration map (60 *) queries Fields.duration,
      date map Date.formatter.print queries Fields.date,
      hasAiQueries,
      (hasAi | true).fold(aiLevel queries Fields.ai, Nil),
      toQueries(perf, Fields.perf),
      toQueries(source, Fields.source),
      toQueries(rated, Fields.rated),
      toQueries(status, Fields.status),
      toQueries(analysed, Fields.analysed),
      toQueries(whiteUser, Fields.whiteUser),
      toQueries(blackUser, Fields.blackUser)
    ).flatten match {
        case Nil     => matchAllQuery
        case queries => must(queries: _*)
      })
  }

  def usernames = List(user1, user2).flatten

  private def hasAiQueries = hasAi.toList map { a =>
    a.fold(existsQuery(Fields.ai), not(existsQuery(Fields.ai)))
  }

  private def toQueries(query: Option[_], name: String) = query.toList map {
    case s: String => termQuery(name, s.toLowerCase)
    case x         => termQuery(name, x)
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
