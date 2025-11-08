package lila.search

import cats.Monad
import cats.mtl.Raise
import cats.mtl.implicits.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.{ ElasticError, Index as ESIndex, Response }

extension (queries: List[Query])
  def compile: Query = queries match
    case Nil => matchAllQuery()
    case q :: Nil => q
    case _ => boolQuery().filter(queries)

extension (index: Index)
  def toES: ESIndex = ESIndex(index.value)

  def mapping = index match
    case Index.Forum => forum.Mapping.fields
    case Index.Ublog => ublog.Mapping.fields
    case Index.Game => game.Mapping.fields
    case Index.Study => study.Mapping.fields
    case Index.Team => team.Mapping.fields

  def refreshInterval =
    index match
      case Index.Study => "10s"
      case _ => "300s"

extension [F[_]: Monad, A](response: Response[A])
  def toResult: Raise[F, ElasticError] ?=> F[A] =
    response.fold(response.error.raise)(_.pure[F])
  def unitOrFail: Raise[F, ElasticError] ?=> F[Unit] =
    response.fold(response.error.raise)(_ => ().pure[F])

trait HasStringId[A]:
  extension (a: A) def id: String
