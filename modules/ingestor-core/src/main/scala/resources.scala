package lila.search
package ingestor

import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.typelevel.otel4s.metrics.MeterProvider

class AppResources(
    val lichess: MongoDatabase[IO],
    val study: MongoDatabase[IO],
    val studyLocal: MongoDatabase[IO],
    val elastic: ESClient[IO],
    val store: KVStore
)

object AppResources:

  def instance(conf: AppConfig)(using MeterProvider[IO]): Resource[IO, AppResources] =
    (
      conf.mongo.makeMongoClient,
      conf.mongo.makeStudyMongoClient,
      conf.mongo.makeStudyOplogClient,
      makeElasticClient(conf.elastic),
      KVStore(conf.kvStorePath).toResource
    ).parMapN(AppResources.apply)

  private def makeElasticClient(conf: ElasticConfig)(using MeterProvider[IO]): Resource[IO, ESClient[IO]] =
    val metrics = OtelMetrics
      .clientMetricsOps[IO]()
      .map(org.http4s.client.middleware.Metrics[IO](_, _.uri.renderString.some))

    (metrics.toResource, EmberClientBuilder.default[IO].build)
      .mapN(_.apply(_))
      .map(ESClient(conf.uri))
