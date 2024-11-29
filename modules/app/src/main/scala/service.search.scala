package lila.search
package app

import cats.effect.*
import io.github.arainko.ducktape.*
import lila.search.forum.Forum
import lila.search.game.Game
import lila.search.spec.*
import lila.search.study.Study
import lila.search.team.Team
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.metrics.{ Histogram, Meter }
import org.typelevel.otel4s.{ Attribute, AttributeKey, Attributes }
import smithy4s.Timestamp

import java.time.Instant
import java.util.concurrent.TimeUnit

class SearchServiceImpl(esClient: ESClient[IO], metric: Histogram[IO, Double])(using
    LoggerFactory[IO]
) extends SearchService[IO]:

  import SearchServiceImpl.{ *, given }

  given logger: Logger[IO] = LoggerFactory[IO].getLogger

  private val baseAttributes = Attributes(Attribute("http.request.method", "POST"))
  private val countMetric =
    metric
      .recordDuration(
        TimeUnit.MILLISECONDS,
        withErrorType(
          baseAttributes
            .added(MetricKeys.httpRoute, s"/api/count/")
        )
      )

  private val searchMetric =
    metric
      .recordDuration(
        TimeUnit.MILLISECONDS,
        withErrorType(
          baseAttributes
            .added(MetricKeys.httpRoute, s"/api/count/")
        )
      )

  private def countRecord[A](f: IO[A])  = countMetric.surround(f)
  private def searchRecord[A](f: IO[A]) = searchMetric.surround(f)

  override def count(query: Query): IO[CountOutput] =
    countRecord:
      esClient
        .count(query)
        .map(CountOutput.apply)
        .handleErrorWith: e =>
          logger.error(e)(s"Error in count: query=$query") *>
            IO.raiseError(InternalServerError("Internal server error"))

  override def search(query: Query, from: From, size: Size): IO[SearchOutput] =
    searchRecord:
      esClient
        .search(query, from, size)
        .map(SearchOutput.apply)
        .handleErrorWith: e =>
          logger.error(e)(s"Error in search: query=$query, from=$from, size=$size") *>
            IO.raiseError(InternalServerError("Internal server error"))

object SearchServiceImpl:

  given Transformer.Derived[Timestamp, Instant] =
    Transformer.Derived.FromFunction(_.toInstant)

  given intRange: Transformer.Derived[IntRange, Range[Int]] =
    Transformer.Derived.FromFunction(r => Range(r.a, r.b))

  given dateRange: Transformer.Derived[DateRange, Range[Instant]] =
    Transformer.Derived.FromFunction(r => Range(r.a.map(_.to[Instant]), r.b.map(_.to[Instant])))

  given Queryable[Query] with
    def searchDef(query: Query)(from: From, size: Size) =
      query match
        case q: Query.Forum => q.to[Forum].searchDef(from, size)
        case q: Query.Game  => q.to[Game].searchDef(from, size)
        case q: Query.Study => q.to[Study].searchDef(from, size)
        case q: Query.Team  => q.to[Team].searchDef(from, size)

    def countDef(query: Query) =
      query match
        case q: Query.Forum => q.to[Forum].countDef
        case q: Query.Game  => q.to[Game].countDef
        case q: Query.Study => q.to[Study].countDef
        case q: Query.Team  => q.to[Team].countDef

    def index(query: Query) = query match
      case _: Query.Forum => Index.Forum
      case _: Query.Game  => Index.Game
      case _: Query.Study => Index.Study
      case _: Query.Team  => Index.Team

  def apply(elastic: ESClient[IO])(using Meter[IO], LoggerFactory[IO]): IO[SearchService[IO]] =
    Meter[IO]
      .histogram[Double]("http.server.request.duration")
      .withUnit("ms")
      .create
      .map(new SearchServiceImpl(elastic, _))

  object MetricKeys:
    val httpRoute = AttributeKey.string("http.route")
    val errorType = AttributeKey.string("error.type")

  import lila.search.ESClient.MetricKeys.*
  def withErrorType(static: Attributes)(ec: Resource.ExitCase): Attributes = ec match
    case Resource.ExitCase.Succeeded =>
      static
    case Resource.ExitCase.Errored(e) =>
      static.added(errorType, e.getClass.getName)
    case Resource.ExitCase.Canceled =>
      static.added(errorType, "canceled")
