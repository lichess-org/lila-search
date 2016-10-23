package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.QueryDefinition
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
    field(name) typed StringType boost 5,
    field(owner) typed StringType boost 2,
    field(members) typed StringType boost 1,
    field(chapterNames) typed StringType boost 3,
    field(chapterTexts) typed StringType boost 1,
    field(likes) typed ShortType,
    field(public) typed BooleanType)
}

case class Query(text: String, userId: Option[String]) extends lila.search.Query {

  def searchDef(from: From, size: Size) = index =>
    search in index.toString query makeQuery sort (
      field sort "_score" order SortOrder.DESC,
      field sort Fields.likes order SortOrder.DESC
    ) start from.value size size.value

  def countDef = index => search in index.toString query makeQuery size 0

  private lazy val parsed = QueryParser(text, List("owner", "member"))

  private lazy val makeQuery = must {
    parsed.terms.map { term =>
      multiMatchQuery(term) fields (Query.searchableFields: _*)
    } ::: List(
      parsed("owner") map { termQuery(Fields.owner, _) },
      parsed("member") map { termQuery(Fields.members, _) }
    ).flatten
  } should List(
    Some(selectPublic),
    userId map selectUserId
  ).flatten minimumShouldMatch 1

  private val selectPublic = termQuery(Fields.public, true)

  private def selectUserId(userId: String) = termQuery(Fields.members, userId)
}

object Query {

  private val searchableFields = List(
    Fields.name,
    Fields.owner,
    Fields.members,
    Fields.chapterNames,
    Fields.chapterTexts)

  implicit val jsonReader = play.api.libs.json.Json.reads[Query]
}
