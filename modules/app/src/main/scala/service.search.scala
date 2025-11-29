package lila.search
package app

import cats.effect.*
import cats.mtl.Handle.*
import io.github.arainko.ducktape.*
import lila.search.forum.Forum
import lila.search.game.Game
import lila.search.spec.*
import lila.search.team.Team
import lila.search.ublog.Ublog
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import smithy4s.Timestamp

import java.time.Instant
import lila.search.study2.Study2

class SearchServiceImpl(esClient: ESClient[IO])(using LoggerFactory[IO]) extends SearchService[IO]:

  import SearchServiceImpl.given

  private val logger: Logger[IO] = LoggerFactory[IO].getLogger

  override def count(query: Query): IO[CountOutput] =
    allow:
      esClient.count(query)
    .rescue: e =>
      logger.error(e.asException)(s"Error in count: query=${query.toString}") *>
        IO.raiseError(InternalServerError("Internal server error"))
    .map(CountOutput.apply)

  override def search(query: Query, from: From, size: Size): IO[SearchOutput] =
    allow:
      esClient.search(query, from, size)
    .rescue: e =>
      logger.error(e.asException)(s"Error in search: query=${query.toString}, from=$from, size=$size") *>
        IO.raiseError(InternalServerError("Internal server error"))
    .map(SearchOutput.apply)

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
          case q: Query.Study => q.to[Study2].searchDef(from, size)
          case q: Query.Team => q.to[Team].searchDef(from, size)

      def countDef =
        query match
          case q: Query.Forum => q.to[Forum].countDef
          case q: Query.Ublog => q.to[Ublog].countDef
          case q: Query.Game => q.to[Game].countDef
          case q: Query.Study => q.to[Study2].countDef
          case q: Query.Team => q.to[Team].countDef

      def index = query match
        case _: Query.Forum => Index.Forum
        case _: Query.Ublog => Index.Ublog
        case _: Query.Game => Index.Game
        case _: Query.Study => Index.Study2
        case _: Query.Team => Index.Team
