package lila.search
package ublog

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

case class Ublog(queryText: String, byDate: Boolean, minQuality: Option[Int], language: Option[String]):

  def searchDef(from: From, size: Size) =
    val req = search(Ublog.index)
      .query(makeQuery())
      .fetchSource(false)

    val sorted =
      if byDate then
        req.sortBy(
          fieldSort(Fields.quality).order(SortOrder.DESC).missing("_last"),
          fieldSort(Fields.date).order(SortOrder.DESC)
        )
      else req

    sorted
      .start(from.value)
      .size(size.value)

  def countDef = count(Ublog.index).query(makeQuery())

  private def makeQuery() =
    val parsed    = QueryParser(queryText, Nil)
    val baseQuery =
      if parsed.terms.isEmpty then matchAllQuery()
      else
        multiMatchQuery(parsed.terms.mkString(" "))
          .fields(Ublog.searchableFields*)
          .matchType("most_fields")
    boolQuery()
      .must(baseQuery)
      .filter(
        List(
          minQuality.map(f => rangeQuery(Fields.quality).gte(f)),
          language.map(l => termQuery(Fields.language, l))
        ).flatten
      )

object Ublog:
  val index                    = "ublog"
  private val searchableFields = List(Fields.text)

object Fields:
  val text     = "text"
  val quality  = "quality"
  val language = "language"
  val date     = "date"

object Mapping:
  import Fields.*
  def fields =
    Seq(
      textField(text),
      shortField(quality).copy(docValues = Some(true)),
      keywordField(language).copy(docValues = Some(false)),
      dateField(date).copy(docValues = Some(true))
    )
