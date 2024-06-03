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
  given query: lila.search.Queryable[Forum] = new lila.search.Queryable[Forum]:

    def searchDef(query: Forum)(from: From, size: Size) =
      index =>
        search(index.name)
          .query(makeQuery(query))
          .fetchSource(false)
          .sortBy(fieldSort(Fields.date).order(SortOrder.DESC))
          .start(from.value)
          .size(size.value)

    def countDef(query: Forum) = index => search(index.name).query(makeQuery(query)) size 0

    private def parsed(text: String) = QueryParser(text, List("user"))

    private def makeQuery(query: Forum) = boolQuery().must(
      parsed(query.text).terms.map { term =>
        multiMatchQuery(term).fields(searchableFields*)
      } ::: List(
        parsed(query.text)("user").map { termQuery(Fields.author, _) },
        (!query.troll).option(termQuery(Fields.troll, false))
      ).flatten
    )

  private val searchableFields = List(Fields.body, Fields.topic, Fields.author)
