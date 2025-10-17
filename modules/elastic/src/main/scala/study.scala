package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
import lila.search.study.Study.Sorting

case class Study(text: String, sorting: Option[Sorting], userId: Option[String]):

  def searchDef(from: From, size: Size) =
    search(Study.index)
      .query(makeQuery())
      .fetchSource(false)
      .sortBy(sorting.map(_.toElastic) ++ Seq(fieldSort("_score").order(SortOrder.DESC)))
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
  val nameRaw = "raw"
  val owner = "owner"
  val members = "members"
  val chapterNames = "chapterNames"
  val chapterTexts = "chapterTexts"
  val topics = "topics"
  val createdAt = "createdAt_date"
  val updatedAt = "updatedAt_date"
  val rank = "rank"
  val likes = "likes"
  val public = "public"

object Mapping:
  import Fields.*
  def fields =
    Seq(
      textField(name)
        .copy(boost = Some(10), analyzer = Some("english"))
        .copy(fields = List(keywordField(nameRaw))),
      keywordField(owner).copy(boost = Some(2), docValues = Some(false)),
      keywordField(members).copy(boost = Some(1), docValues = Some(false)),
      textField(chapterNames).copy(boost = Some(4), analyzer = Some("english")),
      textField(chapterTexts).copy(boost = Some(1), analyzer = Some("english")),
      textField(topics).copy(boost = Some(5), analyzer = Some("english")),
      shortField(likes).copy(docValues = Some(true)), // sort by likes
      booleanField(public).copy(docValues = Some(false)),
      dateField(rank).copy(format = Some(SearchDateTime.format)),
      dateField(createdAt).copy(format = Some(SearchDateTime.format)),
      dateField(updatedAt).copy(format = Some(SearchDateTime.format))
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

  enum Field(val field: String):
    case Name extends Field(s"${Fields.name}.${Fields.nameRaw}")
    case Likes extends Field(Fields.likes)
    case CreatedAt extends Field(Fields.createdAt)
    case UpdatedAt extends Field(Fields.updatedAt)
    case Hot extends Field(Fields.rank)

  enum Order:
    case Asc, Desc

  extension (o: Order)
    def toElastic: SortOrder = o match
      case Order.Asc => SortOrder.ASC
      case Order.Desc => SortOrder.DESC

  case class Sorting(field: Field, order: Order):
    def toElastic: FieldSort =
      fieldSort(field.field).order(order.toElastic)
