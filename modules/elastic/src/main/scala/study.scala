package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

case class Study(text: String, userId: Option[String]):

  def searchDef(from: From, size: Size) =
    search(Study.index)
      .query(makeQuery())
      .fetchSource(false)
      .sortBy(
        fieldSort("_score").order(SortOrder.DESC),
        fieldSort(Fields.likes).order(SortOrder.DESC)
      )
      .start(from.value)
      .size(size.value)

  def countDef = count(Study.index).query(makeQuery())

  private def makeQuery() = {
    val parsed = QueryParser(text, List("owner", "member"))
    val matcher: Query =
      if parsed.terms.isEmpty then matchAllQuery()
      else
        multiMatchQuery(parsed.terms.mkString(" "))
          .fields(Study.searchableFields*)
          .analyzer("english")
          .matchType("most_fields")
    boolQuery()
      .must:
        matcher :: List(
          parsed("owner").map(termQuery(Fields.owner, _)),
          parsed("member").map(member =>
            boolQuery().must(termQuery(Fields.members, member)).not(termQuery(Fields.owner, member))
          )
        ).flatten
      .should(
        List(
          Some(selectPublic),
          userId.map(selectUserId)
        ).flatten
      )
  }.minimumShouldMatch(1)

  private val selectPublic = termQuery(Fields.public, true)

  private def selectUserId(userId: String) = termQuery(Fields.members, userId)

object Fields:
  val name = "name"
  val owner = "owner"
  val members = "members"
  val chapterNames = "chapterNames"
  val chapterTexts = "chapterTexts"
  val topics = "topics"
  // val createdAt = "createdAt"
  // val updatedAt = "updatedAt"
  // val rank = "rank"
  val likes = "likes"
  val public = "public"

object Mapping:
  import Fields.*
  def fields =
    Seq(
      textField(name).copy(boost = Some(10), analyzer = Some("english")),
      keywordField(owner).copy(boost = Some(2), docValues = Some(false)),
      keywordField(members).copy(boost = Some(1), docValues = Some(false)),
      textField(chapterNames).copy(boost = Some(4), analyzer = Some("english")),
      textField(chapterTexts).copy(boost = Some(1), analyzer = Some("english")),
      textField(topics).copy(boost = Some(5), analyzer = Some("english")),
      shortField(likes).copy(docValues = Some(true)), // sort by likes
      booleanField(public).copy(docValues = Some(false))
    )

object Study:
  val index = "study"

  private val searchableFields = List(
    Fields.name,
    Fields.owner,
    Fields.members,
    Fields.topics,
    Fields.chapterNames,
    Fields.chapterTexts
  )
