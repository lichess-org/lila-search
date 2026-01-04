package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.count.CountRequest
import com.sksamuel.elastic4s.requests.searches.SearchRequest
import com.sksamuel.elastic4s.requests.searches.queries.{ NestedQuery, Query }
import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
import lila.search.study.Study.Sorting

case class Study(
    text: String,
    sorting: Option[Sorting],
    userId: Option[String],
    // Chapter-level filters
    chapterName: Option[String] = None,
    chapterDescription: Option[String] = None,
    // Tag-level filters
    variant: Option[String] = None,
    eco: Option[String] = None,
    opening: Option[String] = None,
    playerWhite: Option[String] = None,
    playerBlack: Option[String] = None,
    whiteFideId: Option[String] = None,
    blackFideId: Option[String] = None,
    event: Option[String] = None
):

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
      else boolQuery().should(machStudyQueries(parsed.termsString))

    boolQuery()
      .must(
        matcher ::
          parsed("member").map(member => boolQuery().must(termQuery(Fields.members, member))).toList ++
          allFilters
      )
      .should(
        List(
          Some(selectPublic),
          userId.map(selectUserId)
        ).flatten
      )
  }.minimumShouldMatch(1)

  private def makeOwnerQuery(parsed: ParsedQuery)(owner: String) = {
    val matcher: Query =
      if parsed.terms.isEmpty then matchAllQuery()
      else boolQuery().should(machChapterQuery(parsed.termsString) :: machStudyQueries(parsed.termsString))

    boolQuery()
      .must(
        matcher ::
          termQuery(Fields.owner, owner) ::
          parsed("member").map(member => boolQuery().must(termQuery(Fields.members, member))).toList ++
          allFilters
      )
      .should(
        List(
          Some(selectPublic),
          userId.map(selectUserId)
        ).flatten
      )
  }.minimumShouldMatch(1)

  private def machStudyQueries(text: String) =
    List(
      multiMatchQuery(text)
        .field(Fields.name, 3.0)
        .field(Fields.topics, 2.0)
        .field(Fields.description, 1.0)
        .analyzer("english_with_chess_synonyms")
        .operator("and"),
      multiMatchQuery(text)
        .fields(Fields.owner, Fields.members)
    )

  private def machChapterQuery(text: String) =
    nestedQuery(
      "chapters",
      boolQuery().should(
        multiMatchQuery(text)
          .fields("chapters.name", "chapters.description")
          .analyzer("english_with_chess_synonyms"),
        tagQuery(text)
      )
    )

  def tagQuery(input: String): NestedQuery =
    nestedQuery(
      "chapters.tags",
      boolQuery()
        .should(
          // keyword fields → termQuery (exact match)
          termQuery("chapters.tags.variant", input),
          termQuery("chapters.tags.whiteFideId", input),
          termQuery("chapters.tags.blackFideId", input),
          termQuery("chapters.tags.eco", input),

          // text fields → matchQuery (full‑text / partial)
          matchQuery("chapters.tags.event", input),
          matchQuery("chapters.tags.white", input),
          matchQuery("chapters.tags.black", input),
          matchQuery("chapters.tags.opening", input)
        )
        .minimumShouldMatch(1)
    )

  // Build chapter-level filter queries (single nested)
  private def chapterFilters: List[Query] =
    List(
      chapterName.map(name => nestedQuery("chapters", matchQuery("chapters.name", name))),
      chapterDescription.map(desc => nestedQuery("chapters", matchQuery("chapters.description", desc)))
    ).flatten

  // Build tag-level filter queries (double nested)
  private def tagFilters: List[Query] =
    val tagQueries = List(
      // Keyword fields use termQuery for exact matching
      variant.map(v => termQuery("chapters.tags.variant", v)),
      eco.map(e => termQuery("chapters.tags.eco", e)),
      whiteFideId.map(id => termQuery("chapters.tags.whiteFideId", id)),
      blackFideId.map(id => termQuery("chapters.tags.blackFideId", id)),
      // Text fields use matchQuery for full-text search
      opening.map(o => matchQuery("chapters.tags.opening", o)),
      playerWhite.map(w => matchQuery("chapters.tags.white", w)),
      playerBlack.map(b => matchQuery("chapters.tags.black", b)),
      event.map(e => matchQuery("chapters.tags.event", e))
    ).flatten

    if tagQueries.isEmpty then Nil
    else
      List(
        nestedQuery(
          "chapters",
          nestedQuery("chapters.tags", boolQuery().must(tagQueries))
        )
      )

  // Combine all filters (chapter + tag)
  private def allFilters: List[Query] = chapterFilters ++ tagFilters

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
