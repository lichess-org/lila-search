package lila.search
package team

import com.sksamuel.elastic4s.http.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.searches.queries.QueryDefinition
import com.sksamuel.elastic4s.searches.sort.SortOrder

object Fields {
  val name = "na"
  val description = "de"
  val location = "lo"
  val nbMembers = "nbm"
}

object Mapping {
  import Fields._
  def fields = Seq(
    textField(name) boost 3 analyzer "english" docValues false,
    textField(description) boost 2 analyzer "english" docValues false,
    textField(location) analyzer "english" docValues false,
    shortField(nbMembers)
  )
}

case class Query(text: String) extends lila.search.Query {

  def searchDef(from: From, size: Size) = index =>
    search(index.toString) query makeQuery sortBy (
      fieldSort(Fields.nbMembers) order SortOrder.DESC
    ) start from.value size size.value

  def countDef = index => search(index.toString) query makeQuery size 0

  private lazy val parsed = QueryParser(text, Nil)

  private lazy val makeQuery = must {
    parsed.terms.map { term =>
      multiMatchQuery(term) fields (Query.searchableFields: _*)
    }
  }
}

object Query {

  private val searchableFields = List(Fields.name, Fields.description, Fields.location)

  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}
