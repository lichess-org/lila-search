package lila.search
package app
package test

import cats.effect.{ IO, Resource }
import lila.search.spec.{ HealthService, SearchService }
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import smithy4s.http4s.*

object Clients:

  def health(uri: Uri): Resource[IO, HealthService[IO]] =
    EmberClientBuilder
      .default[IO]
      .build
      .flatMap: client =>
        SimpleRestJsonBuilder
          .apply(HealthService)
          .client(client)
          .uri(uri)
          .resource

  def search(uri: Uri): Resource[IO, SearchService[IO]] =
    EmberClientBuilder
      .default[IO]
      .build
      .flatMap: client =>
        SimpleRestJsonBuilder
          .apply(SearchService)
          .client(client)
          .uri(uri)
          .resource
