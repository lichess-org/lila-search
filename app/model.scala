package lila.search

import com.sksamuel.elastic4s.RichSearchResponse
import org.elasticsearch.action.count.{ CountResponse => CR }

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

  def apply[A](res: RichSearchResponse): SearchResponse =
    SearchResponse(res.getHits.hits.toList map (_.id))
}

case class CountResponse(count: Int)

object CountResponse {

  def apply[A](res: CR): CountResponse =
    CountResponse(res.getCount.toInt)
}

