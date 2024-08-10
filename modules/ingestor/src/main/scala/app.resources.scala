package lila.search
package ingestor

import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import com.mongodb.ReadPreference
import mongo4cats.client.MongoClient
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.Logger

class AppResources(
    val lichess: MongoDatabase[IO],
    val study: MongoDatabase[IO],
    val studyLocal: MongoDatabase[IO],
    val elastic: ESClient[IO],
    val store: KVStore
)

object AppResources:

  def instance(conf: AppConfig)(using Logger[IO]): Resource[IO, AppResources] =
    (
      makeMongoClient(conf.mongo),
      makeStudyMongoClient(conf.mongo),
      makeStudyOplogClient(conf.mongo),
      makeElasticClient(conf.elastic),
      KVStore.apply().toResource
    ).parMapN(AppResources.apply)

  def makeElasticClient(conf: ElasticConfig) =
    ESClient.apply(conf.uri)

  def makeMongoClient(conf: MongoConfig) =
    MongoClient
      .fromConnectionString[IO](conf.uri)
      .evalMap(_.getDatabase(conf.name).map(_.withReadPreference(ReadPreference.secondary())))

  def makeStudyMongoClient(conf: MongoConfig) =
    MongoClient
      .fromConnectionString[IO](conf.studyUri)
      .evalMap(_.getDatabase(conf.studyName))

  def makeStudyOplogClient(conf: MongoConfig) =
    MongoClient
      .fromConnectionString[IO](conf.studyUri)
      .evalMap(_.getDatabase("local"))
