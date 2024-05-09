package lila.search
package game

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import scala.concurrent.duration._

object Fields {
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
}

object Mapping {
  import Fields._
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
      dateField(date).copy(format = Some(Date.format), docValues = Some(true)),
      intField(duration).copy(docValues = Some(false)),
      intField(clockInit).copy(docValues = Some(false)),
      shortField(clockInc).copy(docValues = Some(false)),
      booleanField(analysed).copy(docValues = Some(false)),
      keywordField(whiteUser).copy(docValues = Some(false)),
      keywordField(blackUser).copy(docValues = Some(false)),
      keywordField(source).copy(docValues = Some(false))
    )
}

object GameQuery {
  implicit val query: lila.search.Queryable[Game] = new lila.search.Queryable[Game] {

    val timeout = 5.seconds

    def searchDef(query: Game)(from: From, size: Size) =
      index =>
        (search(index.name)
          .query(
            makeQuery(query)
          )
          .sortBy(query.sorting.definition)
          .start(from.value) size size.value).timeout(timeout)

    def countDef(query: Game) = index => (search(index.name).query(makeQuery(query)) size 0).timeout(timeout)

    private def makeQuery(query: Game) = {

      import query._
      def usernames = List(user1, user2).flatten

      def hasAiQueries =
        hasAi.toList.map { a =>
          a.fold(existsQuery(Fields.ai), not(existsQuery(Fields.ai)))
        }

      def toQueries(query: Option[?], name: String) =
        query.toList.map {
          case s: String => termQuery(name, s.toLowerCase)
          case x         => termQuery(name, x)
        }

      List(
        usernames.map { termQuery(Fields.uids, _) },
        toQueries(winner, Fields.winner),
        toQueries(loser, Fields.loser),
        toQueries(winnerColor, Fields.winnerColor),
        turns.queries(Fields.turns),
        averageRating.queries(Fields.averageRating),
        duration.queries(Fields.duration),
        clock.init.queries(Fields.clockInit),
        clock.inc.queries(Fields.clockInc),
        date.map(Date.formatter.print).queries(Fields.date),
        hasAiQueries,
        (hasAi.getOrElse(true)).fold(aiLevel.queries(Fields.ai), Nil),
        if (perf.nonEmpty) List(termsQuery(Fields.perf, perf)) else Nil,
        toQueries(source, Fields.source),
        toQueries(rated, Fields.rated),
        toQueries(status, Fields.status),
        toQueries(analysed, Fields.analysed),
        toQueries(whiteUser, Fields.whiteUser),
        toQueries(blackUser, Fields.blackUser)
      ).flatten match {
        case Nil     => matchAllQuery()
        case queries => boolQuery().must(queries)
      }

    }
  }
}

case class Sorting(f: String, order: String) {
  import com.sksamuel.elastic4s.requests.searches.sort.SortOrder
  def definition =
    fieldSort {
      (Sorting.fieldKeys contains f).fold(f, Sorting.default.f)
    }.order((order.toLowerCase == "asc").fold(SortOrder.ASC, SortOrder.DESC))
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
  def inc  = Range(incMin, incMax)
}