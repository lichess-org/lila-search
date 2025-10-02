package lila.search
package client

import akka.util.ByteString
import com.github.plokhotnyuk.jsoniter_scala.core.*
import lila.search.spec.*
import play.api.libs.ws.{ BodyReadable, BodyWritable, InMemoryBody, StandaloneWSClient }
import smithy4s.json.Json.given
import smithy4s.schema.Schema

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NoStackTrace

/**
 * This error is thrown when a search request fails.
 */
enum SearchError extends NoStackTrace:
  case BadRequest(message: String)
  case InternalServerError(message: String)

  /**
   * This error is thrown when object serialization fails.
   */
  case JsonWriterError(message: String)

class PlaySearchClient(client: StandaloneWSClient, baseUrl: String)(using ExecutionContext)
    extends SearchClient:

  import PlaySearchClient.*
  import implicits.given

  override def count(query: Query): Future[CountOutput] =
    request(s"$baseUrl/count", SearchInput(query))

  override def search(query: Query, from: From, size: Size): Future[SearchOutput] =
    request(s"$baseUrl/search/${from.value}/${size.value}", SearchInput(query))

  private def request[D: Schema, R: Schema](url: String, data: D): Future[R] =
    try
      client
        .url(url)
        .post(data)
        .flatMap:
          case res if res.status == 200 => Future.successful(res.body[R])
          case res if res.status == 400 =>
            Future.failed(SearchError.BadRequest(s"$url ${res.status} ${res.body}"))
          case res => Future.failed(SearchError.InternalServerError(s"$url ${res.status} ${res.body}"))
    catch case e: JsonWriterException => Future.failed(SearchError.JsonWriterError(e.toString))

object PlaySearchClient:
  opaque type SearchInput = Query
  object SearchInput:
    inline def apply(query: Query): SearchInput = query
    extension (s: SearchInput) inline def query: Query = s

object implicits:

  import PlaySearchClient.*
  import smithy4s.schema.Schema.struct

  given Schema[SearchInput] = struct(
    Query.schema.required[SearchInput]("query", _.query)
  )(SearchInput.apply)

  given [A] => JsonCodec[A] => BodyWritable[A] =
    BodyWritable(a => InMemoryBody(ByteString.fromArrayUnsafe(writeToArray(a))), "application/json")

  given [A] => JsonCodec[A] => BodyReadable[A] =
    BodyReadable(res => readFromArray(res.bodyAsBytes.toArray))

  def apply(client: StandaloneWSClient, url: String)(using ExecutionContext): SearchService[Future] =
    PlaySearchClient(client, url)
