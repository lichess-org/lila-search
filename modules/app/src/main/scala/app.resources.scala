package lila.search
package app

import cats.effect.*
import cats.effect.unsafe.IORuntime
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.otel4s.metrics.Meter

class AppResources(val esClient: ESClient[IO])

object AppResources:

  def instance(conf: AppConfig)(using Meter[IO], IORuntime): Resource[IO, AppResources] =
    val uri = Uri.fromString(conf.elastic.uri).fold(throw _, identity)
    EmberClientBuilder
      .default[IO]
      .build
      .evalMap(ESClient(_, uri))
      .map(AppResources.apply)
