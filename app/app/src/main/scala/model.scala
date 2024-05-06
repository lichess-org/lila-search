package lila.search

import com.sksamuel.elastic4s.requests.searches.{ SearchResponse => ESR }

case class Id(value: String)

case class StringQuery(value: String)
case class From(value: Int)
case class Size(value: Int)

case class SearchResponse(hitIds: List[String])

object SearchResponse {

  def apply(res: ESR): SearchResponse =
    SearchResponse(res.hits.hits.toList map (_.id))
}

case class CountResponse(count: Int)

object CountResponse {

  def apply(res: ESR): CountResponse =
    CountResponse(res.totalHits.toInt)
}
