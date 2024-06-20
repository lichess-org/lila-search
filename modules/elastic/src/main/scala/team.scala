package lila.search
package team

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

case class Team(text: String)

private object Fields:
  val name        = "na"
  val description = "de"
  val nbMembers   = "nbm"

object Mapping:
  import Fields.*
  def fields =
    Seq(
      textField(name).copy(boost = Some(10), analyzer = Some("english")),
      textField(description).copy(boost = Some(2), analyzer = Some("english")),
      shortField(nbMembers)
    )

object TeamQuery:
  val index = "team"

  given query: Queryable[Team] = new:

    def searchDef(query: Team)(from: From, size: Size) =
      search(index)
        .query(makeQuery(query))
        .fetchSource(false)
        .sortBy(fieldSort(Fields.nbMembers).order(SortOrder.DESC))
        .start(from.value)
        .size(size.value)

    def countDef(query: Team) = count(index).query(makeQuery(query))

    private def makeQuery(team: Team) =
      QueryParser(team.text, Nil).terms.map(term => multiMatchQuery(term).fields(searchableFields*)).compile

  private val searchableFields = List(Fields.name, Fields.description)
