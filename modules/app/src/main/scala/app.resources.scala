package lila.search
package app

import cats.effect.*
import cats.syntax.all.*
import lila.search.clickhouse.ClickHouseClient
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.typelevel.otel4s.metrics.MeterProvider

class AppResources(
    val esClient: ESClient[IO],
    val chClient: ClickHouseClient[IO]
)

object AppResources:

  def instance(conf: AppConfig)(using MeterProvider[IO]): Resource[IO, AppResources] =
    val chResource: Resource[IO, ClickHouseClient[IO]] = conf.gameBackend match
      case GameSearchBackend.ElasticOnly => Resource.pure(ClickHouseClient.noop)
      case GameSearchBackend.ClickHouseOnly => ClickHouseClient.resource(conf.clickhouse)

    (makeElasticClient(conf.elastic), chResource).parMapN(AppResources.apply)

  private def makeElasticClient(conf: ElasticConfig)(using MeterProvider[IO]): Resource[IO, ESClient[IO]] =
    val metrics = OtelMetrics
      .clientMetricsOps[IO]()
      .map(org.http4s.client.middleware.Metrics[IO](_, _.uri.renderString.some))

    (metrics.toResource, EmberClientBuilder.default[IO].build)
      .mapN(_.apply(_))
      .map(ESClient(conf.uri))
