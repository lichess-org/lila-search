package lila.search
package game

import org.joda.time.DateTime

import com.sksamuel.elastic4s.http.ElasticDsl.{ RichFuture => _, _ }
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
  val clockInit = "ct"
  val clockInc = "ci"
  val analysed = "n"
  val whiteUser = "wu"
  val blackUser = "bu"
  val source = "so"
}

object Mapping {
  import Fields._
  def fields = Seq(
    shortField(status),
    shortField(turns),
    booleanField(rated),
    shortField(perf),
    keywordField(uids),
    keywordField(winner),
    shortField(winnerColor),
    shortField(averageRating),
    shortField(ai),
    dateField(date) format Date.format,
    intField(duration),
    intField(clockInit),
    intField(clockInc),
    booleanField(analysed),
    keywordField(whiteUser),
    keywordField(blackUser),
    shortField(source)
  )
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
    clock: Clocking = Clocking(),
    sorting: Sorting = Sorting.default,
    analysed: Option[Boolean] = None,
    whiteUser: Option[String] = None,
    blackUser: Option[String] = None
) extends lila.search.Query {

  import Fields._

  def searchDef(from: From, size: Size) = index =>
    search(index.toString) query makeQuery sortBy sorting.definition start from.value size size.value

  def countDef = index => search(index.toString) query makeQuery size 0

  private lazy val makeQuery = boolQuery().must(List(
    usernames map { termQuery(Fields.uids, _) },
    toQueries(winner, Fields.winner),
    toQueries(winnerColor, Fields.winnerColor),
    turns queries Fields.turns,
    averageRating queries Fields.averageRating,
    duration queries Fields.duration,
    clock.init queries Fields.clockInit,
    clock.inc queries Fields.clockInc,
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
      case Nil => matchAllQuery
      case queries => must(queries)
    })

  def usernames = List(user1, user2).flatten

  private def hasAiQueries = hasAi.toList map { a =>
    a.fold(existsQuery(Fields.ai), not(existsQuery(Fields.ai)))
  }

  private def toQueries(query: Option[_], name: String) = query.toList map {
    case s: String => termQuery(name, s.toLowerCase)
    case x => termQuery(name, x)
  }
}

object Query {

  import play.api.libs.json._
  import play.api.libs.json.JodaReads._

  private implicit val sortingJsonReader = Json.reads[Sorting]
  private implicit val clockingJsonReader = Json.reads[Clocking]
  implicit val dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)
  implicit val dateRangeJsonReader: Reads[Range[DateTime]] = Range.rangeJsonReader[DateTime]
  implicit val jsonReader = Json.reads[Query]
}

case class Sorting(f: String, order: String) {
  import org.elasticsearch.search.sort.SortOrder
  def definition =
    fieldSort {
      (Sorting.fieldKeys contains f).fold(f, Sorting.default.f)
    } order (order.toLowerCase == "asc").fold(SortOrder.ASC, SortOrder.DESC)
}
object Sorting {

  val default = Sorting(Fields.date, "desc")

  val fieldKeys = List(Fields.date, Fields.turns, Fields.averageRating)
}

case class Clocking(
    initMin: Option[Int] = None,
    initMax: Option[Int] = None,
    incMin: Option[Int] = None,
    incMax: Option[Int] = None
) {

  def init = Range(initMin, initMax)
  def inc = Range(incMin, incMax)
}
