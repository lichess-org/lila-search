package lila.search
package app

import cats.effect.*
import cats.mtl.Handle.*
import cats.syntax.all.*
import io.github.arainko.ducktape.*
import lila.search.clickhouse.ClickHouseClient
import lila.search.forum.Forum
import lila.search.game.Game
import lila.search.spec.*
import lila.search.study.Study
import lila.search.team.Team
import lila.search.ublog.Ublog
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.*
import smithy4s.Timestamp

import java.time.Instant

class SearchServiceImpl(
    esClient: ESClient[IO],
    chClient: ClickHouseClient[IO],
    gameBackend: GameSearchBackend,
    gameMetrics: GameMetrics,
    dualMetrics: DualMetrics
)(using LoggerFactory[IO])
    extends SearchService[IO]:

  import SearchServiceImpl.given

  private val logger: Logger[IO] = LoggerFactory[IO].getLogger

  override def count(query: Query): IO[CountOutput] =
    query match
      case q: Query.Game => gameCount(q)
      case _ => esCount(query)

  override def search(query: Query, from: From, size: Size): IO[SearchOutput] =
    query match
      case q: Query.Game => gameSearch(q, from, size)
      case _ => esSearch(query, from, size)

  // --- game dispatch ---

  private def gameSearch(q: Query.Game, from: From, size: Size): IO[SearchOutput] =
    gameMetrics.recordSearch:
      gameBackend match
        case GameSearchBackend.ElasticOnly => esSearch(q, from, size)
        case GameSearchBackend.ClickHouseOnly => chSearch(q, from, size)
        case GameSearchBackend.Dual => dualSearch(q, from, size)

  private def gameCount(q: Query.Game): IO[CountOutput] =
    gameMetrics.recordCount:
      gameBackend match
        case GameSearchBackend.ElasticOnly => esCount(q)
        case GameSearchBackend.ClickHouseOnly => chCount(q)
        case GameSearchBackend.Dual => dualCount(q)

  // --- dual (shadow) mode ---

  private def timed[A](io: IO[A]): IO[(A, Double)] =
    IO.monotonic.flatMap: start =>
      io.flatMap: a =>
        IO.monotonic.map: end =>
          (a, (end - start).toUnit(java.util.concurrent.TimeUnit.MILLISECONDS))

  private def dualSearch(q: Query.Game, from: From, size: Size): IO[SearchOutput] =
    (timed(esSearch(q, from, size)), timed(chSearch(q, from, size)).attempt).parTupled
      .flatMap:
        case ((esResult, esMs), chOutcome) =>
          dualMetrics.recordLatency(esMs, "elastic", "search") *>
            (chOutcome match
              case Right((chResult, chMs)) =>
                dualMetrics.recordLatency(chMs, "clickhouse", "search") *>
                  dualMetrics.recordDiff("search", esResult.hitIds.size, chResult.hitIds.size)
              case Left(e) =>
                dualMetrics.recordError("search") *>
                  logger.error(e)("dual search: CH query failed")
            ).start.void.as(esResult)

  private def dualCount(q: Query.Game): IO[CountOutput] =
    (timed(esCount(q)), timed(chCount(q)).attempt).parTupled
      .flatMap:
        case ((esResult, esMs), chOutcome) =>
          dualMetrics.recordLatency(esMs, "elastic", "count") *>
            (chOutcome match
              case Right((chResult, chMs)) =>
                dualMetrics.recordLatency(chMs, "clickhouse", "count") *>
                  dualMetrics.recordDiff("count", esResult.count.toInt, chResult.count.toInt)
              case Left(e) =>
                dualMetrics.recordError("count") *>
                  logger.error(e)("dual count: CH query failed")
            ).start.void.as(esResult)

  // --- per-backend wrappers ---

  private def chSearch(q: Query.Game, from: From, size: Size): IO[SearchOutput] =
    chClient.searchGames(q.to[Game], from, size).map(ids => SearchOutput(ids.map(Id.apply)))

  private def chCount(q: Query.Game): IO[CountOutput] =
    chClient.countGames(q.to[Game]).map(n => CountOutput(n.toInt))

  private def esSearch(query: Query, from: From, size: Size): IO[SearchOutput] =
    allow:
      esClient.search(query, from, size)
    .rescue: e =>
      logger.error(e.asException)(s"Error in search: query=${query.toString}") *>
        IO.raiseError(InternalServerError("Internal server error"))
    .map(SearchOutput.apply)

  private def esCount(query: Query): IO[CountOutput] =
    allow:
      esClient.count(query)
    .rescue: e =>
      logger.error(e.asException)(s"Error in count: query=${query.toString}") *>
        IO.raiseError(InternalServerError("Internal server error"))
    .map(CountOutput.apply)

class DualMetrics(
    latency: Histogram[IO, Double],
    resultDiff: Histogram[IO, Double],
    errors: Counter[IO, Long]
):
  def recordLatency(ms: Double, backend: String, operation: String): IO[Unit] =
    latency.record(ms, Attribute("backend", backend), Attribute("operation", operation))

  def recordDiff(operation: String, esCount: Int, chCount: Int): IO[Unit] =
    resultDiff.record(
      (esCount - chCount).abs.toDouble,
      Attribute("operation", operation)
    )

  def recordError(operation: String): IO[Unit] =
    errors.add(1L, Attribute("operation", operation))

object DualMetrics:

  def make(using MeterProvider[IO]): IO[DualMetrics] =
    MeterProvider[IO]
      .get("game.dual")
      .flatMap: meter =>
        (
          meter
            .histogram[Double]("game.dual.latency")
            .withUnit("ms")
            .withDescription("Game search latency by backend")
            .create,
          meter
            .histogram[Double]("game.dual.result.diff")
            .withDescription("Absolute difference in result count between ES and CH")
            .create,
          meter
            .counter[Long]("game.dual.error")
            .withDescription("CH shadow query errors")
            .create
        ).mapN(DualMetrics.apply)

  val noop: DualMetrics = DualMetrics(
    Histogram.noop[IO, Double],
    Histogram.noop[IO, Double],
    Counter.noop[IO, Long]
  )

class GameMetrics(duration: Histogram[IO, Double]):

  import GameMetrics.withErrorType

  private val searchDuration =
    duration.recordDuration(
      java.util.concurrent.TimeUnit.MILLISECONDS,
      withErrorType(Attribute("operation", "search"))
    )

  private val countDuration =
    duration.recordDuration(
      java.util.concurrent.TimeUnit.MILLISECONDS,
      withErrorType(Attribute("operation", "count"))
    )

  def recordSearch[A](io: IO[A]): IO[A] = searchDuration.surround(io)
  def recordCount[A](io: IO[A]): IO[A] = countDuration.surround(io)

object GameMetrics:

  def make(using MeterProvider[IO]): IO[GameMetrics] =
    MeterProvider[IO]
      .get("game")
      .flatMap:
        _.histogram[Double]("game.request.duration")
          .withUnit("ms")
          .withDescription("Game search/count request duration")
          .create
      .map(GameMetrics(_))

  private def withErrorType(static: Attribute[String])(ec: Resource.ExitCase) =
    ec match
      case Resource.ExitCase.Succeeded => Seq(static)
      case Resource.ExitCase.Errored(e) => Seq(static, Attribute("error.type", e.getClass.getName))
      case Resource.ExitCase.Canceled => Seq(static, Attribute("error.type", "canceled"))

object SearchServiceImpl:

  given Transformer.Derived[Timestamp, Instant] =
    Transformer.Derived.FromFunction(_.toInstant)

  given intRange: Transformer.Derived[IntRange, Range[Int]] =
    Transformer.Derived.FromFunction(r => Range(r.a, r.b))

  given dateRange: Transformer.Derived[DateRange, Range[Instant]] =
    Transformer.Derived.FromFunction(r => Range(r.a.map(_.to[Instant]), r.b.map(_.to[Instant])))

  given Queryable[Query]:
    extension (query: Query)
      def searchDef(from: From, size: Size) =
        query match
          case q: Query.Forum => q.to[Forum].searchDef(from, size)
          case q: Query.Ublog => q.to[Ublog].searchDef(from, size)
          case q: Query.Game => q.to[Game].searchDef(from, size)
          case q: Query.Study => q.to[Study].searchDef(from, size)
          case q: Query.Team => q.to[Team].searchDef(from, size)

      def countDef =
        query match
          case q: Query.Forum => q.to[Forum].countDef
          case q: Query.Ublog => q.to[Ublog].countDef
          case q: Query.Game => q.to[Game].countDef
          case q: Query.Study => q.to[Study].countDef
          case q: Query.Team => q.to[Team].countDef

      def index = query match
        case _: Query.Forum => Index.Forum
        case _: Query.Ublog => Index.Ublog
        case _: Query.Game => Index.Game
        case _: Query.Study => Index.Study
        case _: Query.Team => Index.Team
