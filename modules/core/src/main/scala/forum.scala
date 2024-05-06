package lila.search
package forum

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder
import play.api.libs.json.Reads

object Fields {
  val body    = "bo"
  val topic   = "to"
  val topicId = "ti"
  val author  = "au"
  val troll   = "tr"
  val date    = "da"
}

object Mapping {
  import Fields._
  def fields =
    Seq(
      textField(body).copy(boost = Some(2), analyzer = Some("english")),
      textField(topic).copy(boost = Some(5), analyzer = Some("english")),
      keywordField(author).copy(docValues = Some(false)),
      keywordField(topicId).copy(docValues = Some(false)),
      booleanField(troll).copy(docValues = Some(false)),
      dateField(date)
    )
}

case class Query(text: String, troll: Boolean) extends lila.search.Query {

  def searchDef(from: From, size: Size) =
    index =>
      search(index.name) query makeQuery sortBy (
        fieldSort(Fields.date) order SortOrder.DESC
      ) start from.value size size.value

  def countDef = index => search(index.name) query makeQuery size 0

  private lazy val parsed = QueryParser(text, List("user"))

  private lazy val makeQuery = boolQuery().must(
    parsed.terms.map { term =>
      multiMatchQuery(term) fields (Query.searchableFields: _*)
    } ::: List(
      parsed("user") map { termQuery(Fields.author, _) },
      !troll option termQuery(Fields.troll, false)
    ).flatten
  )
}

object Query {

  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)

  implicit val jsonReader: Reads[Query] = play.api.libs.json.Json.reads[Query]
}
