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
import com.sksamuel.elastic4s.Indexable
import smithy4s.schema.Schema

class SearchServiceImpl(esClient: ESClient[IO])(using Logger[IO]) extends SearchService[IO]:

  import SearchServiceImpl.{ given, * }

  override def store(source: Source, id: String): IO[Unit] =
    esClient
      .store(source.index, Id(id), source)
      .handleErrorWith: e =>
        error"Error in store: source=$source, id=$id" *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def refresh(index: Index): IO[Unit] =
    esClient
      .refreshIndex(index.transform)
      .handleErrorWith: e =>
        error"Error in refresh: index=$index" *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def mapping(index: Index): IO[Unit] =
    esClient
      .putMapping(index.transform, index.mapping)
      .handleErrorWith: e =>
        error"Error in mapping: index=$index" *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def deleteById(index: Index, id: String): IO[Unit] =
    esClient
      .deleteOne(index.transform, Id(id))
      .handleErrorWith: e =>
        error"Error in deleteById: index=$index, id=$id" *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def deleteByIds(index: Index, ids: List[String]): IO[Unit] =
    esClient
      .deleteMany(index.transform, ids.map(Id))
      .handleErrorWith: e =>
        error"Error in deleteByIds: index=$index, ids=$ids" *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def count(query: Query): IO[CountResponse] =
    esClient
      .count(query.index, query)
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

  extension (index: Index)
    def transform: lila.search.Index = lila.search.Index(index.value)
    def mapping = index match
      case Index.Forum => forum.Mapping.fields
      case Index.Game  => game.Mapping.fields
      case Index.Study => study.Mapping.fields
      case Index.Team  => team.Mapping.fields

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
      case q: Query.Forum => lila.search.Index("forum")
      case q: Query.Game  => lila.search.Index("game")
      case q: Query.Study => lila.search.Index("study")
      case q: Query.Team  => lila.search.Index("team")

  // given Indexable[Source] = (s: Source) => writeToString(s)

  import smithy4s.json.Json.given
  import com.github.plokhotnyuk.jsoniter_scala.core._
  given Schema[Source.ForumSource] = lila.search.spec.Source.ForumSource.schema
  given Schema[Source.GameSource]  = lila.search.spec.Source.GameSource.schema
  given Schema[Source.StudySource] = lila.search.spec.Source.StudySource.schema
  given Schema[Source.TeamSource]  = lila.search.spec.Source.TeamSource.schema

  given Indexable[Source] = (s: Source) =>
    s match
      case s: Source.ForumSource => writeToString[Source.ForumSource](s)
      case s: Source.GameSource  => writeToString[Source.GameSource](s)
      case s: Source.StudySource => writeToString[Source.StudySource](s)
      case s: Source.TeamSource  => writeToString[Source.TeamSource](s)

  extension (source: Source)
    def index = source match
      case s: Source.ForumSource => lila.search.Index("forum")
      case s: Source.GameSource  => lila.search.Index("game")
      case s: Source.StudySource => lila.search.Index("study")
      case s: Source.TeamSource  => lila.search.Index("team")
