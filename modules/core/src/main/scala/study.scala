package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture as _, * }
import com.sksamuel.elastic4s.requests.searches.queries.Query as QueryDefinition
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

object Fields:
  val name         = "name"
  val owner        = "owner"
  val members      = "members"
  val chapterNames = "chapterNames"
  val chapterTexts = "chapterTexts"
  val topics       = "topics"
  // val createdAt = "createdAt"
  // val updatedAt = "updatedAt"
  // val rank = "rank"
  val likes  = "likes"
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

object StudyQuery:
  given query: lila.search.Queryable[Study] = new lila.search.Queryable[Study]:

    def searchDef(query: Study)(from: From, size: Size) =
      index =>
        search(index.name)
          .query(makeQuery(query))
          .fetchSource(false)
          .sortBy(
            fieldSort("_score").order(SortOrder.DESC),
            fieldSort(Fields.likes).order(SortOrder.DESC)
          )
          .start(from.value)
          .size(size.value)

    def countDef(query: Study) = index => search(index.name).query(makeQuery(query)) size 0

    private def parsed(text: String) = QueryParser(text, List("owner", "member"))

    private def makeQuery(query: Study) = {
      val matcher: QueryDefinition =
        if parsed(query.text).terms.isEmpty then matchAllQuery()
        else
          multiMatchQuery(
            parsed(query.text).terms.mkString(" ")
          ).fields(searchableFields*).analyzer("english").matchType("most_fields")
      must {
        matcher :: List(
          parsed(query.text)("owner").map { termQuery(Fields.owner, _) },
          parsed(query.text)("member").map { member =>
            boolQuery()
              .must(termQuery(Fields.members, member))
              .not(termQuery(Fields.owner, member))
          }
        ).flatten
      } should List(
        Some(selectPublic),
        query.userId.map(selectUserId)
      ).flatten
    }.minimumShouldMatch(1)

    private val selectPublic = termQuery(Fields.public, true)

    private def selectUserId(userId: String) = termQuery(Fields.members, userId)

  private val searchableFields = List(
    Fields.name,
    Fields.owner,
    Fields.members,
    Fields.topics,
    Fields.chapterNames,
    Fields.chapterTexts
  )
