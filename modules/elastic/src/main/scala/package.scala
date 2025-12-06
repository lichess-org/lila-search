package lila.search

import cats.Monad
import cats.mtl.Raise
import cats.mtl.implicits.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.fields.ElasticField
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.{ ElasticError, Index as ESIndex, Response }
import com.sksamuel.elastic4s.analysis.*

trait HasStringId[A]:
  extension (a: A) def id: String

extension (queries: List[Query])
  def compile: Query = queries match
    case Nil => matchAllQuery()
    case q :: Nil => q
    case _ => boolQuery().filter(queries)

extension (index: Index)
  def toES: ESIndex = ESIndex(index.value)

  def mapping: List[ElasticField] = index match
    case Index.Forum => forum.Mapping.fields
    case Index.Ublog => ublog.Mapping.fields
    case Index.Game => game.Mapping.fields
    case Index.Study => study.Mapping.fields
    case Index.Study2 => study2.Mapping.fields
    case Index.Team => team.Mapping.fields

  def keepSource: Boolean = index match
    case Index.Forum => false
    case Index.Ublog => false
    case Index.Game => false
    case Index.Study => false
    case Index.Study2 => true // need source for partial updates (likes and ranks)
    case Index.Team => false

  def refreshInterval =
    index match
      case Index.Study => "10s"
      case Index.Study2 => "10s"
      case _ => "300s"

  def analysis: Option[Analysis] =
    index match
      case Index.Study => chessAnalysis.some
      case _ => none

extension [F[_]: Monad, A](response: Response[A])
  def toResult: Raise[F, ElasticError] ?=> F[A] =
    response.fold(response.error.raise)(_.pure[F])
  def unitOrFail: Raise[F, ElasticError] ?=> F[Unit] =
    response.fold(response.error.raise)(_ => ().pure[F])

val chessAnalysis = Analysis(
  analyzers = List(
    CustomAnalyzer(
      name = "english_with_chess_synonyms",
      tokenizer = "standard",
      tokenFilters = List(
        "english_possessive_stemmer",
        "lowercase",
        "english_stop",
        "chess_synonyms_filter",
        "english_stemmer"
      )
    )
  ),
  tokenFilters = List(
    StopTokenFilter(
      "english_stop",
      stopwords = List("_english_")
    ),
    StemmerTokenFilter(
      "english_stemmer",
      "english"
    ),
    StemmerTokenFilter(
      "english_possessive_stemmer",
      "possessive_english"
    ),
    SynonymTokenFilter(
      "chess_synonyms_filter",
      path = Some("synonyms/chess_synonyms.txt")
    )
  )
)

