package lila.search
package study

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.count.CountRequest
import com.sksamuel.elastic4s.requests.searches.SearchRequest
import com.sksamuel.elastic4s.requests.searches.queries.matches.MultiMatchQuery
import com.sksamuel.elastic4s.requests.searches.queries.{ NestedQuery, Query }
import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
import lila.search.study.Study.{ ChapterMode, Sorting, TagFilter }

case class Study(
    text: String,
    sorting: Option[Sorting],
    userId: Option[String],
    chapter: Option[ChapterMode] = None
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

  // Per-mode optional `should` clauses keyed off the top-level text:
  //   - SearchText / Filters: chapter name/description nested multi-match
  //   - None + owner-compat: legacy full chapter+tag matchChapterQuery
  //   - None (public, or owner with compat off): nothing
  private def chapterTextQueries(text: String, isOwnerQuery: Boolean): List[Query] =
    if text.isEmpty then Nil
    else
      chapter match
        case Some(_) => List(chapterNameDescQuery(text))
        case None if isOwnerQuery && Study.ownerCompatibility => List(machChapterQuery(text))
        case None => Nil

  // mode 3: structured per-field filters
  private def chapterStructuredFilters: List[Query] =
    chapter match
      case Some(ChapterMode.Filters(cf)) => tagFilters(cf)
      case _ => Nil

  private def studyMatcher(parsed: ParsedQuery, isOwnerQuery: Boolean): Query =
    if parsed.terms.isEmpty then matchAllQuery()
    else
      boolQuery().should(
        chapterTextQueries(parsed.termsString, isOwnerQuery) ++ machStudyQueries(parsed.termsString)
      )

  private def chapterNameDescQuery(text: String): Query =
    nestedQuery(
      "chapters",
      multiMatchQuery(text)
        .fields("chapters.name", "chapters.description")
        .analyzer("english_with_chess_synonyms")
    )

  private def makePublicQuery(parsed: ParsedQuery) =
    boolQuery()
      .must(
        studyMatcher(parsed, isOwnerQuery = false) ::
          parsed("member").map(member => boolQuery().must(termQuery(Fields.members, member))).toList ++
          chapterStructuredFilters
      )
      .should(
        List(
          Some(selectPublic),
          userId.map(selectUserId)
        ).flatten
      )
      .minimumShouldMatch(1)

  private def makeOwnerQuery(parsed: ParsedQuery)(owner: String) =
    boolQuery()
      .must(
        termQuery(Fields.owner, owner) ::
          parsed("member").map(member => boolQuery().must(termQuery(Fields.members, member))).toList ++
          (studyMatcher(parsed, isOwnerQuery = true) :: chapterStructuredFilters)
      )
      .should(
        List(
          Some(selectPublic),
          userId.map(selectUserId)
        ).flatten
      )
      .minimumShouldMatch(1)

  private def machStudyQueries(text: String): List[MultiMatchQuery] =
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

    nestedQuery(
      "chapters",
      boolQuery().should(
        multiMatchQuery(text)
          .fields("chapters.name", "chapters.description")
          .analyzer("english_with_chess_synonyms"),
        tagQuery(text)
      )
    )

  // Build tag-level filter queries (double nested)
  private def tagFilters(cf: TagFilter): List[Query] =
    val tagQueries = List(
      // Keyword fields use termQuery for exact matching
      cf.variant.map(v => termQuery("chapters.tags.variant", v)),
      cf.eco.map(e => termQuery("chapters.tags.eco", e)),
      cf.whiteFideId.map(id => termQuery("chapters.tags.whiteFideId", id)),
      cf.blackFideId.map(id => termQuery("chapters.tags.blackFideId", id)),
      // Text fields use matchQuery for full-text search
      cf.opening.map(o => matchQuery("chapters.tags.opening", o)),
      cf.playerWhite.map(w => matchQuery("chapters.tags.white", w)),
      cf.playerBlack.map(b => matchQuery("chapters.tags.black", b)),
      cf.event.map(e => matchQuery("chapters.tags.event", e))
    ).flatten

    if tagQueries.isEmpty then Nil
    else
      List(
        nestedQuery(
          "chapters",
          nestedQuery("chapters.tags", boolQuery().must(tagQueries))
        )
      )

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
  def fields = MappingGenerator.generateFields(es.StudySource.schema)

object Study:

  // When true, owner-prefixed queries with no chapter filters implicitly
  // run machChapterQuery (legacy behaviour). Disable to require explicit
  // ChapterMode.SearchText.
  val ownerCompatibility: Boolean =
    sys.env.get("STUDY_OWNER_COMPATIBILITY").forall(_.toLowerCase != "false")

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

  enum ChapterMode:
    case SearchText
    case Filters(value: TagFilter)

  case class TagFilter(
      variant: Option[String] = None,
      eco: Option[String] = None,
      opening: Option[String] = None,
      playerWhite: Option[String] = None,
      playerBlack: Option[String] = None,
      whiteFideId: Option[String] = None,
      blackFideId: Option[String] = None,
      event: Option[String] = None
  )
