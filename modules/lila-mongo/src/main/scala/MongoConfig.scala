package lila.search
package ingestor

import cats.effect.{ IO, Resource }
import com.mongodb.ReadPreference
import mongo4cats.client.MongoClient
import mongo4cats.database.MongoDatabase

case class MongoConfig(uri: String, name: String, studyUri: String, studyName: String):

  def makeMongoClient: Resource[IO, MongoDatabase[IO]] =
    MongoClient
      .fromConnectionString[IO](uri)
      .evalMap(_.getDatabase(name).map(_.withReadPreference(ReadPreference.secondary())))

  def makeStudyMongoClient: Resource[IO, MongoDatabase[IO]] =
    MongoClient
      .fromConnectionString[IO](studyUri)
      .evalMap(_.getDatabase(studyName))

  def makeStudyOplogClient: Resource[IO, MongoDatabase[IO]] =
    MongoClient
      .fromConnectionString[IO](studyUri)
      .evalMap(_.getDatabase("local"))
