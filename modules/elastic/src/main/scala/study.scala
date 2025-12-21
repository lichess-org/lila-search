package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.count.CountRequest
import com.sksamuel.elastic4s.requests.searches.SearchRequest
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
import lila.search.study.Study.Sorting

case class Study(text: String, sorting: Option[Sorting], userId: Option[String]):

  def searchDef(from: From, size: Size): SearchRequest =
    search(Index.Study.value)
      .query(makeQuery())
      .sortBy(sorting.map(_.toElastic) ++ Seq(fieldSort("_score").order(SortOrder.DESC)))
      .start(from.value)
      .size(size.value)
      .fetchSource(false)

  def countDef: CountRequest =
    count(Index.Study.value).query(makeQuery())

  private def makeQuery() =
    val parsed = QueryParser(text, List("owner", "member"))
    parsed("owner").fold(makePublicQuery(parsed))(makeOwnerQuery(parsed))

  private def makePublicQuery(parsed: ParsedQuery) = {
    val matcher: Query =
      if parsed.terms.isEmpty then matchAllQuery()
      else
        val text = parsed.terms.mkString(" ")
        boolQuery().should(
          multiMatchQuery(text)
            .field(Fields.name, 3.0)
            .field(Fields.topics, 2.0)
            .field(Fields.description, 1.0)
            .analyzer("english_with_chess_synonyms")
            .operator("and"),
          multiMatchQuery(text)
            .fields(Fields.owner, Fields.members)
        )

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

  private def makeOwnerQuery(parsed: ParsedQuery)(owner: String) = ???

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
