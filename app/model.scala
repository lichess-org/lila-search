package lila.search

import org.elasticsearch.action.count.{ CountResponse => CR }
import org.elasticsearch.action.search.{ SearchResponse => SR }

case class Index(name: String) {
  def withType = s"$name/$name"
}

case class Id(value: String)

case class Query(value: String)

case class SearchResponse(hitIds: List[String])

object SearchResponse {

  val stub = SearchResponse(Nil)

  def apply[A](res: SR): SearchResponse =
    SearchResponse(res.getHits.hits.toList map (_.id))
}

case class CountResponse(count: Int)

object CountResponse {

  val stub = CountResponse(0)

  def apply[A](res: CR): CountResponse =
    CountResponse(res.getCount.toInt)
}

