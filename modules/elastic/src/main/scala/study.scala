package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
import lila.search.study.Study.Sorting
import com.sksamuel.elastic4s.requests.count.CountRequest
import com.sksamuel.elastic4s.requests.searches.SearchRequest

case class Study(text: String, sorting: Option[Sorting], userId: Option[String]):

  def searchDef(from: From, size: Size): SearchRequest =
    search(Study.index)
      .query(makeQuery())
      .sortBy(sorting.map(_.toElastic) ++ Seq(fieldSort("_score").order(SortOrder.DESC)))
      .start(from.value)
      .size(size.value)
      .fetchSource(false)

  def countDef: CountRequest =
    count(Study.index).query(makeQuery())

  private def makeQuery() = {
    val parsed = QueryParser(text, List("owner", "member"))
    val matcher: Query =
      if parsed.terms.isEmpty then matchAllQuery()
      else
        val multiMatcher =
          multiMatchQuery(parsed.terms.mkString(" "))
            .fields(Study.searchables*)
            .analyzer("english_with_chess_synonyms")
            .matchType("most_fields")
        sorting.fold(multiMatcher)(_ => multiMatcher.operator("and"))

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
  val description = "description"
  val owner = "owner"
  val members = "members"
  val topics = "topics"
  val createdAt = "createdAt"
  val updatedAt = "updatedAt"
  val rank = "rank"
  val likes = "likes"
  val public = "public"

object Mapping:
  def fields = MappingGenerator.generateFields(es.Study2Source.schema)

object Study:

  val index = "study"

  private val searchables = List(
    Fields.name,
    Fields.topics,
    Fields.description
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
