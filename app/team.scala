package lila.search
package team

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.QueryDefinition
import org.elasticsearch.search.sort.SortOrder

object Fields {
  val name = "na"
  val description = "de"
  val location = "lo"
  val nbMembers = "nbm"
}

object Mapping {
  import Fields._
  def fields = Seq(
    field(name) typed StringType boost 3 analyzer "english",
    field(description) typed StringType boost 2 analyzer "english",
    field(location) typed StringType,
    field(nbMembers) typed ShortType)
}

case class Query(text: String) extends lila.search.Query {

  def searchDef(from: From, size: Size) = index =>
    search in index.toString query makeQuery sort (
      field sort Fields.nbMembers order SortOrder.DESC
    ) start from.value size size.value

  def countDef = index => search in index.toString query makeQuery size 0

  private lazy val parsed = QueryParser(text, Nil)

  private lazy val makeQuery = parsed.terms match {
    case Nil => all
    case terms => must {
      terms.map { term =>
        multiMatchQuery(term) fields (Query.searchableFields: _*)
      }
    }
  }
}

object Query {

  private val searchableFields = List(Fields.name, Fields.description, Fields.location)

  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}
