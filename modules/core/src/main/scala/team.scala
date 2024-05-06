package lila.search
package team

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

object Fields {
  val name        = "na"
  val description = "de"
  val nbMembers   = "nbm"
}

object Mapping {
  import Fields._
  def fields =
    Seq(
      textField(name).copy(boost = Some(10), analyzer = Some("english")),
      textField(description).copy(boost = Some(2), analyzer = Some("english")),
      shortField(nbMembers)
    )
}

case class Query(text: String) extends lila.search.Query {

  def searchDef(from: From, size: Size) =
    index =>
      search(index.name) query makeQuery sortBy (
        fieldSort(Fields.nbMembers) order SortOrder.DESC
      ) start from.value size size.value

  def countDef = index => search(index.name) query makeQuery size 0

  private lazy val parsed = QueryParser(text, Nil)

  private lazy val makeQuery = must {
    parsed.terms.map { term =>
      multiMatchQuery(term) fields (Query.searchableFields: _*)
    }
  }
}

object Query {

  import play.api.libs.json._

  private val searchableFields = List(Fields.name, Fields.description)

  implicit val jsonReader: Reads[Query] = Json.reads[Query]
}
