package lila.search
package app

import cats.effect.*
import lila.search.spec.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*
import forum.ForumQuery.*
import io.github.arainko.ducktape.*
import org.joda.time.DateTime
import smithy4s.Timestamp

class SearchServiceImpl(esClient: ESClient[IO])(using Logger[IO]) extends SearchService[IO]:

  import SearchServiceImpl.{ given, * }

  override def count(query: Query): IO[CountResponse] =
    esClient
      .count(Index("forum"), query)
      .map(_.to[CountResponse])
      .handleErrorWith: e =>
        error"Error in countForum: query=$query" *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def search(query: Query, from: Int, size: Int): IO[SearchResponse] =
    esClient
      .search(query.index, query, From(from), Size(size))
      .map(_.to[SearchResponse])
      .handleErrorWith: e =>
        error"Error in searchForum: query=$query, from=$from, size=$size" *>
          IO.raiseError(InternalServerError("Internal server error"))

object SearchServiceImpl:

  given Transformer.Derived[Timestamp, DateTime] =
    Transformer.Derived.FromFunction(x => DateTime(x.epochSecond))

  given intRange: Transformer.Derived[Option[IntRange], Range[Int]] =
    Transformer.Derived.FromFunction(_.fold(Range.none)(r => Range(r.a, r.b)))

  given dateRange: Transformer.Derived[Option[DateRange], Range[DateTime]] =
    Transformer.Derived.FromFunction(
      _.fold(Range.none)(r => Range(r.a.map(_.to[DateTime]), r.b.map(_.to[DateTime])))
    )

  given Transformer.Derived[Option[Clocking], game.Clocking] =
    Transformer.Derived.FromFunction(_.fold(game.Clocking())(_.to[game.Clocking]))

  given Transformer.Derived[Option[Sorting], game.Sorting] =
    Transformer.Derived.FromFunction(_.fold(game.Sorting.default)(_.to[game.Sorting]))

  extension (game: Query.Game) def transform: Game = game.to[Game]

  given Queryable[Query] with
    def searchDef(query: Query)(from: From, size: Size) =
      query match
        case q: Query.Forum => forum.ForumQuery.query.searchDef(q.to[Forum])(from, size)
        case q: Query.Game  => game.GameQuery.query.searchDef(q.transform)(from, size)
        case q: Query.Study => study.StudyQuery.query.searchDef(q.to[Study])(from, size)
        case q: Query.Team  => team.TeamQuery.query.searchDef(q.to[Team])(from, size)

    def countDef(query: Query) =
      query match
        case q: Query.Forum => forum.ForumQuery.query.countDef(q.to[Forum])
        case q: Query.Game  => game.GameQuery.query.countDef(q.transform)
        case q: Query.Study => study.StudyQuery.query.countDef(q.to[Study])
        case q: Query.Team  => team.TeamQuery.query.countDef(q.to[Team])

  extension (query: Query)
    def index = query match
      case q: Query.Forum => Index("forum")
      case q: Query.Game  => Index("game")
      case q: Query.Study => Index("study")
      case q: Query.Team  => Index("team")
