package lila.search
package forum

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

case class Forum(text: String, troll: Boolean)

object Fields:
  val body    = "bo"
  val topic   = "to"
  val topicId = "ti"
  val author  = "au"
  val troll   = "tr"
  val date    = "da"

object Mapping:
  import Fields.*
  def fields =
    Seq(
      textField(body).copy(boost = Some(2), analyzer = Some("english")),
      textField(topic).copy(boost = Some(5), analyzer = Some("english")),
      keywordField(author).copy(docValues = Some(false)),
      keywordField(topicId).copy(docValues = Some(false)),
      booleanField(troll).copy(docValues = Some(false)),
      dateField(date)
    )

object ForumQuery:
  given query: Queryable[Forum] = new:
    val index = "forum"

    def searchDef(query: Forum)(from: From, size: Size) =
      search(index)
        .query(makeQuery(query))
        .fetchSource(false)
        .sortBy(fieldSort(Fields.date).order(SortOrder.DESC))
        .start(from.value)
        .size(size.value)

    def countDef(query: Forum) = search(index).query(makeQuery(query)).size(0)

    private def makeQuery(query: Forum) =
      val parsed = QueryParser(query.text, List("user"))
      List(
        parsed.terms.map(term => multiMatchQuery(term).fields(searchableFields*)),
        parsed("user").map(termQuery(Fields.author, _)).toList,
        Option.unless(query.troll)(termQuery(Fields.troll, false)).toList
      ).flatten.match
        case Nil      => matchAllQuery()
        case x :: Nil => x
        case xs       => boolQuery().must(xs)

  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)
