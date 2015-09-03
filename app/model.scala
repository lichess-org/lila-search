package lila.search

import org.elasticsearch.action.count.{ CountResponse => CR }
import org.elasticsearch.action.search.{ SearchResponse => SR }
import com.sksamuel.elastic4s.IndexType

case class Index(name: String, typeName: String) {
  override def toString = s"$name/$typeName"
  def indexType = IndexType(name, typeName)
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

  def apply[A](res: SR): SearchResponse =
    SearchResponse(res.getHits.hits.toList map (_.id))
}

case class CountResponse(count: Int)

object CountResponse {

  def apply[A](res: CR): CountResponse =
    CountResponse(res.getCount.toInt)
}

