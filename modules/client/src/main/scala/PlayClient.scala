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
import scala.annotation.targetName

class PlayClient(client: StandaloneWSClient, baseUrl: String)(using ExecutionContext)
    extends SearchService[Future]:

  import implicits.given

  override def storeBulkTeam(sources: List[TeamSourceWithId]): Future[Unit] =
    client
      .url(s"$baseUrl/store-bulk/team")
      .post(StoreBulkTeamInput(sources))
      .map(_ => ())

  @targetName("storeBulkTeamWithPair")
  def storeBulkTeam(sources: List[(String, TeamSource)]): Future[Unit] =
    storeBulkTeam(sources.map(TeamSourceWithId.apply.tupled))

  override def storeBulkStudy(sources: List[StudySourceWithId]): Future[Unit] =
    client
      .url(s"$baseUrl/store-bulk/study")
      .post(StoreBulkStudyInput(sources))
      .map(_ => ())

  @targetName("storeBulkStudyWithPair")
  def storeBulkStudy(sources: List[(String, StudySource)]): Future[Unit] =
    storeBulkStudy(sources.map(StudySourceWithId.apply.tupled))

  override def storeBulkGame(sources: List[GameSourceWithId]): Future[Unit] =
    client
      .url(s"$baseUrl/store-bulk/game")
      .post(StoreBulkGameInput(sources))
      .map(_ => ())

  @targetName("storeBulkGameWithPair")
  def storeBulkGame(sources: List[(String, GameSource)]): Future[Unit] =
    storeBulkGame(sources.map(GameSourceWithId.apply.tupled))

  override def storeBulkForum(sources: List[ForumSourceWithId]): Future[Unit] =
    client
      .url(s"$baseUrl/store-bulk/forum")
      .post(StoreBulkForumInput(sources))
      .map(_ => ())

  @targetName("storeBulkForumWithPair")
  def storeBulkForum(sources: List[(String, ForumSource)]): Future[Unit] =
    storeBulkForum(sources.map(ForumSourceWithId.apply.tupled))

  override def store(source: Source, id: String): Future[Unit] =
    client
      .url(s"$baseUrl/store/$id")
      .post(source)
      .map(_ => ())

  def storeForum(id: String, source: ForumSource): Future[Unit] =
    client
      .url(s"$baseUrl/store/$id")
      .post(Source.forum(source))
      .map(_ => ())

  def storeGame(id: String, source: GameSource): Future[Unit] =
    client
      .url(s"$baseUrl/store/$id")
      .post(Source.game(source))
      .map(_ => ())

  def storeStudy(id: String, source: StudySource): Future[Unit] =
    client
      .url(s"$baseUrl/store/$id")
      .post(Source.study(source))
      .map(_ => ())

  def storeTeam(id: String, source: TeamSource): Future[Unit] =
    client
      .url(s"$baseUrl/store/$id")
      .post(Source.team(source))
      .map(_ => ())

  override def refresh(index: Index): Future[Unit] =
    client
      .url(s"$baseUrl/refresh/${index.name}")
      .execute("POST")
      .map(_ => ())

  override def mapping(index: Index): Future[Unit] =
    client
      .url(s"$baseUrl/mapping/${index.name}")
      .execute("POST")
      .map(_ => ())

  override def deleteById(index: Index, id: String): Future[Unit] =
    client
      .url(s"$baseUrl/delete/${index.name}/$id")
      .execute("POST")
      .map(_ => ())

  override def deleteByIds(index: Index, ids: List[String]): Future[Unit] =
    client
      .url(s"$baseUrl/delete/${index.name}")
      .post(Ids(ids))
      .map(_ => ())

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

final case class SearchInput(query: Query)

object implicits:

  import smithy4s.schema.Schema.struct

  given Schema[SearchInput] = struct(
    Query.schema.required[SearchInput]("query", _.query)
  )(SearchInput.apply)

  given [A](using JsonCodec[A]): BodyWritable[A] =
    BodyWritable(a => InMemoryBody(ByteString.fromArrayUnsafe(writeToArray(a))), "application/json")

  given [A](using JsonCodec[A]): BodyReadable[A] =
    BodyReadable(res => readFromArray(res.bodyAsBytes.toArray))

  def apply(client: StandaloneWSClient, url: String)(using ExecutionContext): SearchService[Future] =
    PlayClient(client, url)
