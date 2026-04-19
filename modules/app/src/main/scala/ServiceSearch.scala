package lila.search
package app

import cats.effect.*
import cats.mtl.Handle.*
import io.github.arainko.ducktape.*
import lila.search.forum.Forum
import lila.search.game.Game
import lila.search.spec.*
import lila.search.study.Study
import lila.search.team.Team
import lila.search.ublog.Ublog
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.*
import smithy4s.time.Timestamp

import java.time.Instant

class SearchServiceImpl(
    esClient: ESClient[IO],
    gameMetrics: GameMetrics
)(using LoggerFactory[IO])
    extends SearchService[IO]:

  import SearchServiceImpl.given

  private val logger: Logger[IO] = LoggerFactory[IO].getLogger

  override def count(query: Query): IO[CountOutput] =
    query match
      case q: Query.Game => gameMetrics.recordCount(esCount(q))
      case _ => esCount(query)

  override def search(query: Query, from: From, size: Size): IO[SearchOutput] =
    query match
      case q: Query.Game => gameMetrics.recordSearch(esSearch(q, from, size))
      case _ => esSearch(query, from, size)

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
