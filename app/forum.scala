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
    body typed StringType boost 2,
    topic typed StringType boost 4,
    author typed StringType index "not_analyzed",
    topicId typed StringType,
    staff typed BooleanType,
    troll typed BooleanType,
    date typed DateType)
}

case class Query(text: String, staff: Boolean, troll: Boolean) extends lila.search.Query {

  def searchDef(from: From, size: Size) = index =>
    search in index.toString query makeQuery sort (
      field sort Fields.date order SortOrder.DESC
    ) start from.value size size.value

  def countDef = index => count from index.toString query makeQuery

  private lazy val terms = decomposeTextQuery(text)

  private def queryTerms = terms filterNot (_ startsWith "user:")
  private def userSearch = terms find (_ startsWith "user:") map { _ drop 5 }

  private lazy val makeQuery = filteredQuery query {
    queryTerms match {
      case Nil => all
      case terms => must {
        terms.map { term =>
          multiMatchQuery(term) fields (Query.searchableFields: _*)
        }: _*
      }
    }
  } filter {
    List(
      userSearch map { termQuery(Fields.author, _) },
      !staff option termQuery(Fields.staff, false),
      !troll option termQuery(Fields.troll, false)
    ).flatten match {
        case Nil => matchAllQuery
        case filters => must {
          filters: _*
        }
      }
  }
}

object Query {

  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)

  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}
