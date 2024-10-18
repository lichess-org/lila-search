package lila.search
package app

import cats.effect.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.Indexable
import io.github.arainko.ducktape.*
import lila.search.forum.Forum
import lila.search.game.Game
import lila.search.spec.*
import lila.search.study.Study
import lila.search.team.Team
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.metrics.{ Counter, Histogram, Meter }
import org.typelevel.otel4s.{ Attribute, AttributeKey }
import smithy4s.Timestamp
import smithy4s.schema.Schema

import java.time.Instant
import java.util.concurrent.TimeUnit

class SearchServiceImpl(
    esClient: ESClient[IO],
    countErrorCounter: Counter[IO, Long],
    countDuration: Histogram[IO, Double],
    searchErrorCounter: Counter[IO, Long],
    searchDuration: Histogram[IO, Double]
)(using LoggerFactory[IO])
    extends SearchService[IO]:

  import SearchServiceImpl.{ given, * }

  given logger: Logger[IO] = summon[LoggerFactory[IO]].getLogger

  override def storeBulkTeam(sources: List[TeamSourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Team,
        sources.map(s => s.id -> s.source)
      )
      .handleErrorWith: e =>
        logger.error(e)(s"Error in storeBulkTeam: sources=$sources") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def storeBulkStudy(sources: List[StudySourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Study,
        sources.map(s => s.id -> s.source)
      )
      .handleErrorWith: e =>
        logger.error(e)(s"Error in storeBulkStudy: sources=$sources") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def storeBulkGame(sources: List[GameSourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Game,
        sources.map(s => s.id -> s.source)
      )
      .handleErrorWith: e =>
        logger.error(e)(s"Error in storeBulkGame: sources=$sources") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def storeBulkForum(sources: List[ForumSourceWithId]): IO[Unit] =
    esClient
      .storeBulk(
        Index.Forum,
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
      .refreshIndex(index)
      .handleErrorWith: e =>
        logger.error(e)(s"Error in refresh: index=$index") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def mapping(index: Index): IO[Unit] =
    esClient
      .putMapping(index)
      .handleErrorWith: e =>
        logger.error(e)(s"Error in mapping: index=$index") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def deleteById(index: Index, id: String): IO[Unit] =
    esClient
      .deleteOne(index, Id(id))
      .handleErrorWith: e =>
        logger.error(e)(s"Error in deleteById: index=$index, id=$id") *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def deleteByIds(index: Index, ids: List[Id]): IO[Unit] =
    esClient
      .deleteMany(index, ids)
      .handleErrorWith: e =>
        logger.error(e)(s"Error in deleteByIds: index=$index, ids=$ids") *>
          IO.raiseError(InternalServerError("Internal server error"))

  val attributeKey = AttributeKey.string("index")
  override def count(query: Query): IO[CountOutput] =
    countDuration
      .recordDuration(TimeUnit.MILLISECONDS, Attribute(attributeKey, query.index.value))
      .surround:
        esClient
          .count(query)
          .map(CountOutput.apply)
          .handleErrorWith: e =>
            countErrorCounter.inc(Attribute(attributeKey, query.index.value)) *>
              logger.error(e)(s"Error in count: query=$query") *>
              IO.raiseError(InternalServerError("Internal server error"))

  override def search(query: Query, from: From, size: Size): IO[SearchOutput] =
    searchDuration
      .recordDuration(TimeUnit.MILLISECONDS, Attribute(attributeKey, query.index.value))
      .surround:
        esClient
          .search(query, from, size)
          .map(SearchOutput.apply)
          .handleErrorWith: e =>
            searchErrorCounter.inc(Attribute(attributeKey, query.index.value)) *>
              logger.error(e)(s"Error in search: query=$query, from=$from, size=$size") *>
              IO.raiseError(InternalServerError("Internal server error"))

object SearchServiceImpl:

  def apply(
      esClient: ESClient[IO]
  )(using logger: LoggerFactory[IO], meter: Meter[IO]): IO[SearchService[IO]] =
    (
      meter.counter[Long]("count.error").create,
      meter.histogram[Double]("count.duration").withUnit("ms").create,
      meter.counter[Long]("search.error").create,
      meter.histogram[Double]("search.duration").withUnit("ms").create
    ).mapN(new SearchServiceImpl(esClient, _, _, _, _))

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
      case s: Source.ForumCase => Index.Forum
      case s: Source.GameCase  => Index.Game
      case s: Source.StudyCase => Index.Study
      case s: Source.TeamCase  => Index.Team
