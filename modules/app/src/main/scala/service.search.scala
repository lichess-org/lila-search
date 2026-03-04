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
import smithy4s.Timestamp

import java.time.Instant

class SearchServiceImpl(
    esClient: ESClient[IO],
    chClient: ClickHouseClient[IO],
    gameBackend: GameSearchBackend
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
    gameBackend match
      case GameSearchBackend.ElasticOnly => esSearch(q, from, size)
      case GameSearchBackend.ClickHouseOnly => chSearch(q, from, size)
      case GameSearchBackend.Shadow => shadowSearch(q, from, size)

  private def gameCount(q: Query.Game): IO[CountOutput] =
    gameBackend match
      case GameSearchBackend.ElasticOnly => esCount(q)
      case GameSearchBackend.ClickHouseOnly => chCount(q)
      case GameSearchBackend.Shadow => shadowCount(q)

  // --- shadow: CH is primary, ES runs in a detached fiber for comparison only ---
  // CH errors propagate normally. ES errors are logged and swallowed — they never
  // affect the response.

  private def shadowSearch(q: Query.Game, from: From, size: Size): IO[SearchOutput] =
    for
      chFiber <- chSearch(q, from, size).start
      _ <- compareShadow(esSearch(q, from, size), chFiber.joinWithNever).start
      result <- chFiber.joinWithNever
    yield result

  private def shadowCount(q: Query.Game): IO[CountOutput] =
    for
      chFiber <- chCount(q).start
      _ <- compareShadow(esCount(q), chFiber.joinWithNever).start
      result <- chFiber.joinWithNever
    yield result

  private def compareShadow[A](esIO: IO[A], chIO: IO[A]): IO[Unit] =
    (esIO.attempt, chIO)
      .parMapN:
        case (Right(es), ch) if es != ch =>
          logger.warn(s"[shadow] mismatch: ch=${ch.toString} es=${es.toString}"): IO[Unit]
        case (Left(e), _) =>
          logger.warn(e)("[shadow] ES read failed"): IO[Unit]
        case _ => IO.unit
      .flatten

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

object SearchServiceImpl:

  given Transformer.Derived[Timestamp, Instant] =
    Transformer.Derived.FromFunction(_.toInstant)

  given intRange: Transformer.Derived[IntRange, Range[Int]] =
    Transformer.Derived.FromFunction(r => Range(r.a, r.b))

  given dateRange: Transformer.Derived[DateRange, Range[Instant]] =
    Transformer.Derived.FromFunction(r => Range(r.a.map(_.to[Instant]), r.b.map(_.to[Instant])))

  val useStudy2 = sys.env.get("SEARCH_USE_STUDY2").filter(_.toLowerCase == "true").isDefined

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
