package lila.search
package app

import cats.effect.*
import cats.effect.unsafe.IORuntime
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.otel4s.metrics.Meter

class AppResources(val esClient: ESClient[IO])

object AppResources:

  def instance(conf: AppConfig)(using Meter[IO], IORuntime): Resource[IO, AppResources] =
    EmberClientBuilder
      .default[IO]
      .build
      .evalMap(ESClient(conf.elastic.uri))
      .map(AppResources.apply)
