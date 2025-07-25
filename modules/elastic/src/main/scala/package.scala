package lila.search

import cats.MonadThrow
import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.{ Index as ESIndex, Response }

type SourceWithId[A] = (id: String, source: A)

extension (self: Boolean) def fold[A](t: => A, f: => A): A = if self then t else f

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

extension [F[_]: MonadThrow, A](response: Response[A])
  def toResult: F[A] =
    response.fold(response.error.asException.raiseError)(r => r.pure[F])
  def unitOrFail: F[Unit] =
    response.fold(response.error.asException.raiseError)(_ => ().pure[F])
