package lila.search
package forum

import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.searches.queries.QueryDefinition
import com.sksamuel.elastic4s.searches.sort.SortOrder

object Fields {
  val body = "bo"
  val topic = "to"
  val topicId = "ti"
  val author = "au"
  val staff = "st"
  val troll = "tr"
  val date = "da"
}

object Mapping {
  import Fields._
  def fields = Seq(
    textField(body) boost 2 analyzer "english" docValues false,
    textField(topic) boost 5 analyzer "english" docValues false,
    keywordField(author) docValues false,
    keywordField(topicId) docValues false,
    booleanField(staff) docValues false,
    booleanField(troll) docValues false,
    dateField(date)
  )
}

case class Query(text: String, staff: Boolean, troll: Boolean) extends lila.search.Query {

  def searchDef(from: From, size: Size) = index =>
    search(index.toString) query makeQuery sortBy (
      fieldSort(Fields.date) order SortOrder.DESC
    ) start from.value size size.value

  def countDef = index => search(index.toString) query makeQuery size 0

  private lazy val parsed = QueryParser(text, List("user"))

  private lazy val makeQuery = boolQuery().must(
    parsed.terms.map { term =>
      multiMatchQuery(term) fields (Query.searchableFields: _*)
    } ::: List(
      parsed("user") map { termQuery(Fields.author, _) },
      !staff option termQuery(Fields.staff, false),
      !troll option termQuery(Fields.troll, false)
    ).flatten
  )
}

object Query {

  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)

  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}
