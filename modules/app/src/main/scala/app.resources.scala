package lila.search
package app

import cats.effect.*
import cats.syntax.all.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.typelevel.otel4s.metrics.MeterProvider

class AppResources(val esClient: ESClient[IO])

object AppResources:

  def instance(conf: AppConfig)(using MeterProvider[IO]): Resource[IO, AppResources] =
    val metrics = OtelMetrics
      .clientMetricsOps[IO]()
      .map(org.http4s.client.middleware.Metrics[IO](_, _.uri.renderString.some))

    (metrics.toResource, EmberClientBuilder.default[IO].build)
      .mapN(_.apply(_))
      .map(ESClient(conf.elastic.uri))
      .map(AppResources.apply)
