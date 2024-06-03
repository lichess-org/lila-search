package lila.search
package client

import akka.util.ByteString
import com.github.plokhotnyuk.jsoniter_scala.core.*
import lila.search.spec.*
import play.api.libs.ws.BodyWritable
import play.api.libs.ws.InMemoryBody
import play.api.libs.ws.StandaloneWSClient
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import smithy4s.json.Json.given
import smithy4s.schema.Schema
import play.api.libs.ws.BodyReadable
import scala.util.control.NoStackTrace

enum SearchError extends NoStackTrace:
  case BadRequest(message: String)
  case InternalServerError(message: String)

class PlaySearchClient(client: StandaloneWSClient, baseUrl: String)(using ExecutionContext)
    extends SearchClient:

  import implicits.given

  override def storeBulkTeam(sources: List[TeamSourceWithId]): Future[Unit] =
    request_(s"$baseUrl/store-bulk/team", StoreBulkTeamInput(sources))

  override def storeBulkStudy(sources: List[StudySourceWithId]): Future[Unit] =
    request_(s"$baseUrl/store-bulk/study", StoreBulkStudyInput(sources))

  override def storeBulkGame(sources: List[GameSourceWithId]): Future[Unit] =
    request_(s"$baseUrl/store-bulk/game", StoreBulkGameInput(sources))

  override def storeBulkForum(sources: List[ForumSourceWithId]): Future[Unit] =
    request_(s"$baseUrl/store-bulk/forum", StoreBulkForumInput(sources))

  override def store(id: String, source: Source): Future[Unit] =
    request_(s"$baseUrl/store/$id", SourceInput(source))

  override def refresh(index: Index): Future[Unit] =
    request_(s"$baseUrl/refresh/${index.value}")

  override def mapping(index: Index): Future[Unit] =
    request_(s"$baseUrl/mapping/${index.value}")

  override def deleteById(index: Index, id: String): Future[Unit] =
    request_(s"$baseUrl/delete/id/${index.value}/$id")

  override def deleteByIds(index: Index, ids: List[String]): Future[Unit] =
    request_(s"$baseUrl/delete/ids/${index.value}", IdsInput(ids))

  override def count(query: Query): Future[CountOutput] =
    request(s"$baseUrl/count", SearchInput(query))

  override def search(query: Query, from: Int, size: Int): Future[SearchOutput] =
    request(s"$baseUrl/search/$from/$size", SearchInput(query))

  private def request[D: Schema, R: Schema](url: String, data: D): Future[R] =
    try
      client
        .url(url)
        .post(data)
        .flatMap:
          case res if res.status == 200 => Future(res.body[R])
          case res if res.status == 400 =>
            Future.failed(SearchError.BadRequest(s"$url ${res.status} ${res.body}"))
          case res => Future.failed(SearchError.InternalServerError(s"$url ${res.status} ${res.body}"))
    catch case e: JsonWriterException => Future.failed(SearchError.BadRequest(e.getMessage))

  private def request_[D: Schema](url: String, data: D): Future[Unit] =
    try
      client
        .url(url)
        .post(data)
        .flatMap:
          case res if res.status == 200 => Future(())
          case res if res.status == 400 =>
            Future.failed(SearchError.BadRequest(s"$url ${res.status} ${res.body}"))
          case res => Future.failed(SearchError.InternalServerError(s"$url ${res.status} ${res.body}"))
    catch
      case e: JsonWriterException =>
        Future.failed(SearchError.BadRequest(e.getMessage))

  private def request_(url: String): Future[Unit] =
    client
      .url(url)
      .execute("POST")
      .flatMap:
        case res if res.status == 200 => Future(())
        case res if res.status == 400 =>
          Future.failed(SearchError.BadRequest(s"$url ${res.status} ${res.body}"))
        case res => Future.failed(SearchError.InternalServerError(s"$url ${res.status} ${res.body}"))

final private case class SearchInput(query: Query)
final private case class SourceInput(source: Source)
final private case class IdsInput(ids: List[String])

object implicits:

  import smithy4s.schema.Schema.struct

  given Schema[SearchInput] = struct(
    Query.schema.required[SearchInput]("query", _.query)
  )(SearchInput.apply)

  given Schema[SourceInput] = struct(
    Source.schema.required[SourceInput]("source", _.source)
  )(SourceInput.apply)

  given Schema[IdsInput] = struct(
    Ids.schema.required[IdsInput]("ids", x => Ids(x.ids))
  )(x => IdsInput(x.value))

  given [A](using JsonCodec[A]): BodyWritable[A] =
    BodyWritable(a => InMemoryBody(ByteString.fromArrayUnsafe(writeToArray(a))), "application/json")

  given [A](using JsonCodec[A]): BodyReadable[A] =
    BodyReadable(res => readFromArray(res.bodyAsBytes.toArray))

  def apply(client: StandaloneWSClient, url: String)(using ExecutionContext): SearchService[Future] =
    PlaySearchClient(client, url)
