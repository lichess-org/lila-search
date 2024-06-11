package lila.search
package ingestor

import cats.effect.{ IO, Resource }
import mongo4cats.client.MongoClient
import mongo4cats.database.MongoDatabase

class AppResources(val mongo: MongoDatabase[IO])

object AppResources:

  def instance(conf: AppConfig): Resource[IO, AppResources] =
    MongoClient
      .fromConnectionString[IO](conf.uri)
      .evalMap(_.getDatabase(conf.name))
      .map(AppResources(_))
