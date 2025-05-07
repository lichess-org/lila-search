package lila.search
package ingestor

import cats.effect.unsafe.IORuntime
import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import com.mongodb.ReadPreference
import mongo4cats.client.MongoClient
import mongo4cats.database.MongoDatabase
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.otel4s.metrics.Meter

class AppResources(
    val lichess: MongoDatabase[IO],
    val study: MongoDatabase[IO],
    val studyLocal: MongoDatabase[IO],
    val elastic: ESClient[IO],
    val store: KVStore
)

object AppResources:

  def instance(conf: AppConfig)(using Meter[IO], IORuntime): Resource[IO, AppResources] =
    (
      makeMongoClient(conf.mongo),
      makeStudyMongoClient(conf.mongo),
      makeStudyOplogClient(conf.mongo),
      makeElasticClient(conf.elastic),
      KVStore.apply().toResource
    ).parMapN(AppResources.apply)

  private def makeElasticClient(conf: ElasticConfig)(using Meter[IO], IORuntime): Resource[IO, ESClient[IO]] =
    EmberClientBuilder.default[IO].build.evalMap(ESClient(conf.uri))

  private def makeMongoClient(conf: MongoConfig) =
    MongoClient
      .fromConnectionString[IO](conf.uri)
      .evalMap(_.getDatabase(conf.name).map(_.withReadPreference(ReadPreference.secondary())))

  private def makeStudyMongoClient(conf: MongoConfig) =
    MongoClient
      .fromConnectionString[IO](conf.studyUri)
      .evalMap(_.getDatabase(conf.studyName))

  private def makeStudyOplogClient(conf: MongoConfig) =
    MongoClient
      .fromConnectionString[IO](conf.studyUri)
      .evalMap(_.getDatabase("local"))
