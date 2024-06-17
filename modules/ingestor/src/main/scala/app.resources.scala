package lila.search
package ingestor

import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import com.mongodb.ReadPreference
import com.sksamuel.elastic4s.cats.effect.instances.*
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.{ ElasticClient, ElasticProperties }
import mongo4cats.client.MongoClient
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.Logger

class AppResources(val mongo: MongoDatabase[IO], val elastic: ESClient[IO], val store: KVStore)

object AppResources:

  def instance(conf: AppConfig)(using Logger[IO]): Resource[IO, AppResources] =
    (makeMongoClient(conf.mongo), makeElasticClient(conf.elastic), KVStore.apply().toResource)
      .parMapN(AppResources.apply)

  def makeElasticClient(conf: ElasticConfig) =
    Resource
      .make(IO(ElasticClient(JavaClient(ElasticProperties(conf.uri)))))(client => IO(client.close()))
      .map(ESClient.apply[IO])

  def makeMongoClient(conf: MongoConfig) =
    MongoClient
      .fromConnectionString[IO](conf.uri)
      .evalMap(_.getDatabase(conf.name).map(_.withReadPreference(ReadPreference.secondary())))
