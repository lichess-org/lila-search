package lila.search

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query

object Date:
  import org.joda.time.format.{ DateTimeFormat, DateTimeFormatter }
  val format                       = "yyyy-MM-dd HH:mm:ss"
  val formatter: DateTimeFormatter = DateTimeFormat.forPattern(format)

extension (self: Boolean) def fold[A](t: => A, f: => A): A = if self then t else f

extension (queries: List[Query])
  def compile: Query = queries match
    case Nil      => matchAllQuery()
    case q :: Nil => q
    case _        => boolQuery().filter(queries)
