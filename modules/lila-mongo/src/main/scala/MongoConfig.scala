package lila.search
package ingestor

import cats.effect.{ IO, Resource }
import com.mongodb.ReadPreference
import mongo4cats.client.MongoClient
import mongo4cats.database.MongoDatabase

case class MongoDatabaseConfig(
    uri: String,
    name: String,
    readPreference: Option[ReadPreference] = None
):
  def makeClient: Resource[IO, MongoDatabase[IO]] =
    MongoClient
      .fromConnectionString[IO](uri)
      .evalMap: client =>
        client
          .getDatabase(name)
          .map: db =>
            readPreference.fold(db)(db.withReadPreference)

  def makeOplogClient: Resource[IO, MongoDatabase[IO]] =
    MongoClient
      .fromConnectionString[IO](uri)
      .evalMap(_.getDatabase("local"))

case class MongoConfig(
    lichess: MongoDatabaseConfig,
    study: MongoDatabaseConfig
):
  def makeMongoClient: Resource[IO, MongoDatabase[IO]] =
    lichess.copy(readPreference = Some(ReadPreference.secondary())).makeClient

  def makeStudyMongoClient: Resource[IO, MongoDatabase[IO]] =
    study.makeClient

  def makeStudyOplogClient: Resource[IO, MongoDatabase[IO]] =
    study.makeOplogClient

object MongoConfig:
  def apply(uri: String, name: String, studyUri: String, studyName: String): MongoConfig =
    MongoConfig(
      lichess = MongoDatabaseConfig(uri, name),
      study = MongoDatabaseConfig(studyUri, studyName)
    )
