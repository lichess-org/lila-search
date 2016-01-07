package lila.search

import com.sksamuel.elastic4s.RichSearchResponse

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

  def apply(res: RichSearchResponse): SearchResponse =
    SearchResponse(res.hits.toList map (_.id))
}

case class CountResponse(count: Int)

object CountResponse {

  def apply(res: RichSearchResponse): CountResponse =
    CountResponse(res.totalHits.toInt)
}

