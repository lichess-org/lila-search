package lila.search
package app

import cats.effect.*
import com.sksamuel.elastic4s.Indexable
import io.github.arainko.ducktape.*
import lila.search.forum.Forum
import lila.search.game.Game
import lila.search.spec.*
import lila.search.study.Study
import lila.search.team.Team
import org.typelevel.log4cats.Logger
import smithy4s.Timestamp
import smithy4s.schema.Schema

import java.time.Instant

class SearchServiceImpl(esClient: ESClient[IO])(using logger: Logger[IO]) extends SearchService[IO]:

  import SearchServiceImpl.{ given, * }

  override def storeBulkTeam(sources: List[TeamSourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Team.transform,
        sources.map(s => s.id -> s.source)
      )
      .handleErrorWith: e =>
        logger.error(e)(s"Error in storeBulkTeam: sources=$sources") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def storeBulkStudy(sources: List[StudySourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Study.transform,
        sources.map(s => s.id -> s.source)
      )
      .handleErrorWith: e =>
        logger.error(e)(s"Error in storeBulkStudy: sources=$sources") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def storeBulkGame(sources: List[GameSourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Game.transform,
        sources.map(s => s.id -> s.source)
      )
      .handleErrorWith: e =>
        logger.error(e)(s"Error in storeBulkGame: sources=$sources") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def storeBulkForum(sources: List[ForumSourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Forum.transform,
        sources.map(s => s.id -> s.source)
      )
      .handleErrorWith: e =>
        logger.error(e)(s"Error in storeBulkForum: sources=$sources") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def store(id: String, source: Source): IO[Unit] =
    esClient
      .store(source.index, Id(id), source)
      .handleErrorWith: e =>
        logger.error(e)(s"Error in store: source=$source, id=$id") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def refresh(index: Index): IO[Unit] =
    esClient
      .refreshIndex(index.transform)
      .handleErrorWith: e =>
        logger.error(e)(s"Error in refresh: index=$index") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def mapping(index: Index): IO[Unit] =
    esClient
      .putMapping(index.transform, index.mapping)
      .handleErrorWith: e =>
        logger.error(e)(s"Error in mapping: index=$index") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def deleteById(index: Index, id: String): IO[Unit] =
    esClient
      .deleteOne(index.transform, Id(id))
      .handleErrorWith: e =>
        logger.error(e)(s"Error in deleteById: index=$index, id=$id") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def deleteByIds(index: Index, ids: List[String]): IO[Unit] =
    esClient
      .deleteMany(index.transform, ids.map(Id.apply))
      .handleErrorWith: e =>
        logger.error(e)(s"Error in deleteByIds: index=$index, ids=$ids") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def count(query: Query): IO[CountOutput] =
    esClient
      .count(query)
      .map(CountOutput.apply)
      .handleErrorWith: e =>
        logger.error(e)(s"Error in count: query=$query") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def search(query: Query, from: SearchFrom, size: SearchSize): IO[SearchOutput] =
    esClient
      .search(query, from, size)
      .map(xs => SearchOutput(xs.map(_.value)))
      .handleErrorWith: e =>
        logger.error(e)(s"Error in search: query=$query, from=$from, size=$size") *>
          IO.raiseError(InternalServerError("Internal server error"))

object SearchServiceImpl:

  given Transformer.Derived[Timestamp, Instant] =
    Transformer.Derived.FromFunction(_.toInstant)

  given intRange: Transformer.Derived[Option[IntRange], Range[Int]] =
    Transformer.Derived.FromFunction(_.fold(Range.none)(r => Range(r.a, r.b)))

  given dateRange: Transformer.Derived[Option[DateRange], Range[Instant]] =
    Transformer.Derived.FromFunction(
      _.fold(Range.none)(r => Range(r.a.map(_.to[Instant]), r.b.map(_.to[Instant])))
    )

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
    def searchDef(query: Query)(from: SearchFrom, size: SearchSize) =
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

  import smithy4s.json.Json.given
  import com.github.plokhotnyuk.jsoniter_scala.core.*

  given [A: Schema]: Indexable[A] = (a: A) => writeToString(a)
  given Indexable[Source] =
    _ match
      case f: Source.ForumCase => writeToString(f.forum)
      case g: Source.GameCase  => writeToString(g.game)
      case s: Source.StudyCase => writeToString(s.study)
      case t: Source.TeamCase  => writeToString(t.team)

  extension (source: Source)
    def index = source match
      case s: Source.ForumCase => lila.search.Index("forum")
      case s: Source.GameCase  => lila.search.Index("game")
      case s: Source.StudyCase => lila.search.Index("study")
      case s: Source.TeamCase  => lila.search.Index("team")
