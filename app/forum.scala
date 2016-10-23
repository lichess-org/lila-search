package lila.search
package forum

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.QueryDefinition
import org.elasticsearch.search.sort.SortOrder

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
    field(body) typed StringType boost 2,
    field(topic) typed StringType boost 4,
    field(author) typed StringType index "not_analyzed",
    field(topicId) typed StringType,
    field(staff) typed BooleanType,
    field(troll) typed BooleanType,
    field(date) typed DateType)
}

case class Query(text: String, staff: Boolean, troll: Boolean) extends lila.search.Query {

  def searchDef(from: From, size: Size) = index =>
    search in index.toString query makeQuery sort (
      field sort Fields.date order SortOrder.DESC
    ) start from.value size size.value

  def countDef = index => search in index.toString query makeQuery size 0

  private lazy val parsed = QueryParser(text, List("user"))

  private lazy val makeQuery = bool {
    must(
      parsed.terms.map { term =>
        multiMatchQuery(term) fields (Query.searchableFields: _*)
      } ::: List(
        parsed("user") map { termQuery(Fields.author, _) },
        !staff option termQuery(Fields.staff, false),
        !troll option termQuery(Fields.troll, false)
      ).flatten)
  }
}

object Query {

  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)

  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}
