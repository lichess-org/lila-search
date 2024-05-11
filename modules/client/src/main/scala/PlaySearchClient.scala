package lila.search
package client

import akka.util.ByteString
import com.github.plokhotnyuk.jsoniter_scala.core._
import lila.search.spec.*
import play.api.libs.ws.BodyWritable
import play.api.libs.ws.InMemoryBody
import play.api.libs.ws.StandaloneWSClient
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import smithy4s.json.Json.given
import smithy4s.schema.Schema
import play.api.libs.ws.BodyReadable

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
    request_(s"$baseUrl/refresh/${index.name}")

  override def mapping(index: Index): Future[Unit] =
    request_(s"$baseUrl/mapping/${index.name}")

  override def deleteById(index: Index, id: String): Future[Unit] =
    request_(s"$baseUrl/delete/${index.name}/$id")

  override def deleteByIds(index: Index, ids: List[String]): Future[Unit] =
    request_(s"$baseUrl/delete/${index.name}", Ids(ids))

  override def count(query: Query): Future[CountResponse] =
    request(s"$baseUrl/count", SearchInput(query))

  override def search(query: Query, from: Int, size: Int): Future[SearchResponse] =
    request(s"$baseUrl/search/$from/$size", SearchInput(query))

  private def request[D: Schema, R: Schema](url: String, data: D): Future[R] =
    client
      .url(url)
      .post(data)
      .flatMap:
        case res if res.status == 200 => Future(res.body[R])
        case res                      => Future.failed(Exception(s"$url ${res.status} ${res.body}"))

  private def request_[D: Schema](url: String, data: D): Future[Unit] =
    client
      .url(url)
      .post(data)
      .flatMap:
        case res if res.status == 200 => Future(())
        case res                      => Future.failed(Exception(s"$url ${res.status} ${res.body}"))

  private def request_(url: String): Future[Unit] =
    client
      .url(url)
      .execute("POST")
      .flatMap:
        case res if res.status == 200 => Future(())
        case res                      => Future.failed(Exception(s"$url ${res.status} ${res.body}"))

final private case class SearchInput(query: Query)
final private case class SourceInput(source: Source)

object implicits:

  import smithy4s.schema.Schema.struct

  given Schema[SearchInput] = struct(
    Query.schema.required[SearchInput]("query", _.query)
  )(SearchInput.apply)

  given Schema[SourceInput] = struct(
    Source.schema.required[SourceInput]("source", _.source)
  )(SourceInput.apply)

  given [A](using JsonCodec[A]): BodyWritable[A] =
    BodyWritable(a => InMemoryBody(ByteString.fromArrayUnsafe(writeToArray(a))), "application/json")

  given [A](using JsonCodec[A]): BodyReadable[A] =
    BodyReadable(res => readFromArray(res.bodyAsBytes.toArray))

  def apply(client: StandaloneWSClient, url: String)(using ExecutionContext): SearchService[Future] =
    PlaySearchClient(client, url)
