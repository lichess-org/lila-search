package lila.search

import com.sksamuel.elastic4s.http.search.{ SearchResponse => ESR }

case class Index(name: String, typeName: String) {
  override def toString = s"$name/$typeName"
}
object Index {
  def apply(name: String): Index = Index(name, name)
}

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

