package lila.search
package client

import smithy4s.http4s.*
import org.http4s.Uri
import org.http4s.client.Client
import cats.effect.{ IO, Resource }
import org.http4s.ember.client.EmberClientBuilder
import lila.search.spec.*
import scala.concurrent.Future
import cats.effect.unsafe.implicits.global

object Client:

  def apply(uri: Uri): Resource[IO, FutureClient] =
    instance(uri).map(FutureClient(_))

  def instance(uri: Uri): Resource[IO, SearchService[IO]] =
    makeClient.flatMap(makeIO(uri))

  private def makeClient: Resource[IO, Client[IO]] =
    EmberClientBuilder.default[IO].build

  def makeIO(uri: Uri)(client: Client[IO]): Resource[IO, SearchService[IO]] =
    SimpleRestJsonBuilder
      .apply(SearchService)
      .client(client)
      .uri(uri)
      .resource

class FutureClient(io: SearchService[IO]) extends SearchService[Future]:

  override def countForum(text: String, troll: Boolean): Future[CountResponse] =
    io.countForum(text, troll).unsafeToFuture()

  override def searchForum(body: ForumInputBody, from: Int, size: Int): Future[SearchResponse] =
    io.searchForum(body, from, size).unsafeToFuture()
