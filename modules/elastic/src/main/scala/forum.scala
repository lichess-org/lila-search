package lila.search
package forum

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

case class Forum(text: String, troll: Boolean):

  def searchDef(from: From, size: Size) =
    search(Forum.index)
      .query(makeQuery)
      .fetchSource(false)
      .sortBy(fieldSort(Fields.date).order(SortOrder.DESC))
      .start(from.value)
      .size(size.value)

  def countDef = count(Forum.index).query(makeQuery)

  private def makeQuery =
    val parsed = QueryParser(text, List("user"))
    List(
      parsed.terms.map(term => multiMatchQuery(term).fields(Forum.searchableFields*)),
      parsed("user").map(termQuery(Fields.author, _)).toList,
      Option.unless(troll)(termQuery(Fields.troll, false)).toList
    ).flatten.compile

object Forum:
  val index = "forum"
  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)

object Fields:
  val body = "bo"
  val topic = "to"
  val topicId = "ti"
  val author = "au"
  val troll = "tr"
  val date = "da"

object Mapping:
  def fields = MappingGenerator.generateFields(ingestor.ForumSource.schema)
