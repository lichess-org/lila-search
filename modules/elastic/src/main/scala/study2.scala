package lila.search
package study2

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
import lila.search.study2.Study2.Sorting

case class Study2(text: String, sorting: Option[Sorting], userId: Option[String]):

  def searchDef(from: From, size: Size) =
    search(Study2.index)
      .query(makeQuery())
      .fetchSource(false)
      .sortBy(sorting.map(_.toElastic) ++ Seq(fieldSort("_score").order(SortOrder.DESC)))
      .start(from.value)
      .size(size.value)

  def countDef = count(Study2.index).query(makeQuery())

  private def makeQuery() = {
    val parsed = QueryParser(text, List("owner", "member"))
    val matcher: Query =
      if parsed.terms.isEmpty then matchAllQuery()
      else
        multiMatchQuery(parsed.terms.mkString(" "))
          .fields(Study2.searchableFields*)
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
  val description = "description"
  val nameRaw = "raw"
  val owner = "owner"
  val members = "members"
  val topics = "topics"
  val createdAt = "createdAt"
  val updatedAt = "updatedAt"
  val rank = "rank"
  val likes = "likes"
  val public = "public"
  val views = "views"

object Mapping:
  def fields = MappingGenerator.generateFields(es.Study2Source.schema)

object Study2:
  val index = "study2"

  private val searchableFields = List(
    Fields.name,
    Fields.owner,
    Fields.members,
    Fields.topics,
    Fields.description,
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
