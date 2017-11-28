package lila.search
package study

import com.sksamuel.elastic4s.http.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.searches.queries.QueryDefinition
import org.elasticsearch.search.sort.SortOrder

object Fields {
  val name = "name"
  val owner = "owner"
  val members = "members"
  val chapterNames = "chapterNames"
  val chapterTexts = "chapterTexts"
  // val createdAt = "createdAt"
  // val updatedAt = "updatedAt"
  // val rank = "rank"
  val likes = "likes"
  val public = "public"
}

object Mapping {
  import Fields._
  def fields = Seq(
    textField(name) boost 5 analyzer "english",
    keywordField(owner) boost 2,
    keywordField(members) boost 1,
    textField(chapterNames) boost 3 analyzer "english",
    textField(chapterTexts) boost 1 analyzer "english",
    shortField(likes),
    booleanField(public)
  )
}

case class Query(text: String, userId: Option[String]) extends lila.search.Query {

  def searchDef(from: From, size: Size) = index =>
    search(index.toString) query makeQuery sortBy (
      fieldSort("_score") order SortOrder.DESC,
      fieldSort(Fields.likes) order SortOrder.DESC
    ) start from.value size size.value

  def countDef = index => search(index.toString) query makeQuery size 0

  private lazy val parsed = QueryParser(text, List("owner", "member"))

  private lazy val makeQuery = {
    val matcher: QueryDefinition =
      if (parsed.terms.isEmpty) matchAllQuery
      else multiMatchQuery(parsed.terms mkString " ") fields {
        Query.searchableFields.map(_ -> 1f).toMap
      } analyzer "english" matchType "most_fields"
    must {
      matcher :: List(
        parsed("owner") map { termQuery(Fields.owner, _) },
        parsed("member") map { member =>
          boolQuery()
            .must(termQuery(Fields.members, member))
            .not(termQuery(Fields.owner, member))
        }
      ).flatten
    } should List(
      Some(selectPublic),
      userId map selectUserId
    ).flatten
  } minimumShouldMatch 1

  private val selectPublic = termQuery(Fields.public, true)

  private def selectUserId(userId: String) = termQuery(Fields.members, userId)
}

object Query {

  private val searchableFields = List(
    Fields.name,
    Fields.owner,
    Fields.members,
    Fields.chapterNames,
    Fields.chapterTexts
  )

  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}
