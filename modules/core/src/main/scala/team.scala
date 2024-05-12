package lila.search
package team

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.requests.searches.sort.SortOrder

private object Fields {
  val name        = "na"
  val description = "de"
  val nbMembers   = "nbm"
}

object Mapping {
  import Fields._
  def fields =
    Seq(
      textField(name).copy(boost = Some(10), analyzer = Some("english")),
      textField(description).copy(boost = Some(2), analyzer = Some("english")),
      shortField(nbMembers)
    )
}

object TeamQuery {

  implicit val query: lila.search.Queryable[Team] = new lila.search.Queryable[Team] {

    def searchDef(query: Team)(from: From, size: Size) =
      index =>
        search(index.name)
          .query(makeQuery(query))
          .sortBy(
            fieldSort(Fields.nbMembers).order(SortOrder.DESC)
          )
          .start(from.value) size size.value

    def countDef(query: Team) = index => search(index.name).query(makeQuery(query)) size 0

    private def parsed(query: Team) = QueryParser(query.text, Nil)

    private def makeQuery(team: Team) = must {
      parsed(team).terms.map { term =>
        multiMatchQuery(term).fields(searchableFields *)
      }
    }
  }

  private val searchableFields = List(Fields.name, Fields.description)
}
