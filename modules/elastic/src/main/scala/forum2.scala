package lila.search
package forum2

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

case class Forum2(text: String, troll: Boolean, lang: Option[String] = None):

  def searchDef(from: From, size: Size) =
    search(Forum2.index)
      .query(makeQuery)
      .fetchSource(false)
      .sortBy(fieldSort(Fields.date).order(SortOrder.DESC))
      .start(from.value)
      .size(size.value)

  def countDef = count(Forum2.index).query(makeQuery)

  private def makeQuery =
    val langKey = lang.fold(Some("en"))(l => SearchLang.esLangKey(Some(l)))
    val langFields = langKey.toList.flatMap: k =>
      List(s"${Fields.bodyLang}.$k^2", s"${Fields.topicLang}.$k^5")
    val parsed = QueryParser(text, List("user"))
    List(
      parsed.terms.map(term => multiMatchQuery(term).fields((Forum2.searchableFields ++ langFields)*)),
      parsed("user").map(termQuery(Fields.author, _)).toList,
      Option.unless(troll)(termQuery(Fields.troll, false)).toList
    ).flatten.compile

object Forum2:
  val index = "forum2"
  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)

  // Cutover flag (pattern: Study.ownerCompatibility). When true, forum
  // queries are served by the forum2 index with per-language analyzers;
  // when absent/false, the legacy forum index keeps serving.
  val enabled: Boolean =
    sys.env.get("FORUM2_SEARCH").exists(_.toLowerCase == "true")

  given Queryable[Forum2]:
    extension (q: Forum2)
      def searchDef(from: From, size: Size) = q.searchDef(from, size)
      def countDef = q.countDef
      def index: Index = Index.Forum2

object Fields:
  val body = "bo"
  val topic = "to"
  val topicId = "ti"
  val author = "au"
  val troll = "tr"
  val date = "da"
  val bodyLang = "bl"
  val topicLang = "tl"
  val language = "la"

object Mapping:
  def fields = MappingGenerator.generateFields(es.Forum2Source.schema)
