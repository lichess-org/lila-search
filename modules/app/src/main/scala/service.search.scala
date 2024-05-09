package lila.search
package app

import cats.effect.*
import lila.search.spec.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*
import forum.ForumQuery.*
import io.github.arainko.ducktape.*

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

  given Queryable[Query] with
    def searchDef(query: Query)(from: From, size: Size) =
      query match
        case q: Query.Forum => forum.ForumQuery.query.searchDef(q.to[lila.search.Forum])(from, size)
        case q: Query.Team  => team.TeamQuery.query.searchDef(q.to[lila.search.Team])(from, size)
        case q: Query.Study => study.StudyQuery.query.searchDef(q.to[lila.search.Study])(from, size)

    def countDef(query: Query) =
      query match
        case q: Query.Forum => forum.ForumQuery.query.countDef(q.to[lila.search.Forum])
        case q: Query.Team  => team.TeamQuery.query.countDef(q.to[lila.search.Team])
        case q: Query.Study => study.StudyQuery.query.countDef(q.to[lila.search.Study])

  extension (query: Query)
    def index = query match
      case q: Query.Forum => Index("forum")
      case q: Query.Team  => Index("team")
      case q: Query.Study => Index("study")
