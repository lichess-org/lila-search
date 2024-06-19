package lila.search

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.Index as ESIndex
import com.sksamuel.elastic4s.requests.searches.queries.Query

extension (self: Boolean) def fold[A](t: => A, f: => A): A = if self then t else f

extension (queries: List[Query])
  def compile: Query = queries match
    case Nil      => matchAllQuery()
    case q :: Nil => q
    case _        => boolQuery().filter(queries)

extension (index: Index)
  def toES: ESIndex = ESIndex(index.value)

  def mapping = index match
    case Index.Forum => forum.Mapping.fields
    case Index.Game  => game.Mapping.fields
    case Index.Study => study.Mapping.fields
    case Index.Team  => team.Mapping.fields

  def refreshInterval =
    index match
      case Index.Study => "10s"
      case _           => "300s"
