package lila.search
package ublog

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder
import lila.search.ublog.Ublog.SortBy

case class Ublog(
    queryText: String,
    by: SortBy,
    minQuality: Option[Int],
    language: Option[String]
):

  val sanitized = queryText
    .trim()
    .toLowerCase()
    .replaceAll("""([\-=&|><!(){}\[\]^"~*?\\/])""", """\\$1""")
    .replaceAll(" and ", " AND ")
    .replaceAll("\\+", " AND ")
    .split("\\s+")
    .map:
      case s if s.matches("language:[a-z]{2}") || s.matches("quality:[1-3]") => s
      case s => s.replace(":", " ") // devs can use the query string until we get a ui for lang/quality
    .mkString(" ")

  def searchDef(from: From, size: Size) =
    val sortFields =
      (if by == SortBy.score then Seq(scoreSort().order(SortOrder.DESC))
       else if by == SortBy.likes then Seq(fieldSort("likes").order(SortOrder.DESC))
       else Nil) ++ Seq(
        fieldSort("quality").order(SortOrder.DESC).missing("_last"),
        fieldSort("date")
          .order(if by == SortBy.oldest then SortOrder.ASC else SortOrder.DESC)
          .missing("_last")
      )
    search(Ublog.index)
      .query(makeQuery())
      .fetchSource(false)
      .sortBy(sortFields*)
      .start(from.value)
      .size(size.value)

  def countDef = count(Ublog.index).query(makeQuery())

  private def makeQuery() =
    boolQuery()
      .must(queryStringQuery(sanitized).defaultField(Fields.text))
      .filter(
        List(
          minQuality.map(f => rangeQuery(Fields.quality).gte(f)),
          language.map(l => termQuery(Fields.language, l))
        ).flatten
      )

object Ublog:
  val index = "ublog"

  enum SortBy:
    case newest, oldest, score, likes

object Fields:
  val text = "text"
  val likes = "likes"
  val quality = "quality"
  val language = "language"
  val date = "date"

object Mapping:
  def fields = MappingGenerator.generateFields(ingestor.UblogSource.schema)
