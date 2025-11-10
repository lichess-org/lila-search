package lila.search
package team

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

case class Team(text: String):

  def searchDef(from: From, size: Size) =
    search(Team.index)
      .query(makeQuery)
      .fetchSource(false)
      .sortBy(fieldSort(Fields.nbMembers).order(SortOrder.DESC))
      .start(from.value)
      .size(size.value)

  def countDef = count(Team.index).query(makeQuery)

  private def makeQuery =
    QueryParser(text, Nil).terms.map(term => multiMatchQuery(term).fields(Team.searchableFields*)).compile

private object Fields:
  val name = "na"
  val description = "de"
  val nbMembers = "nbm"

object Mapping:
  def fields = MappingGenerator.generateFields(ingestor.TeamSource.schema)

object Team:
  val index = "team"

  private val searchableFields = List(Fields.name, Fields.description)
