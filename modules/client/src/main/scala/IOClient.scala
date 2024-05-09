package lila.search
package client

import smithy4s.http4s.*
import org.http4s.Uri
import org.http4s.client.Client
import cats.effect.IO
import cats.effect.Resource

// the package under which the scala code was generated
import lila.search.spec.*

object Clients:
  def apply(http4sClient: Client[IO], uri: Uri): Resource[IO, SearchService[IO]] =
    SimpleRestJsonBuilder
      .apply(SearchService)
      .client(http4sClient)
      .uri(uri)
      .resource
