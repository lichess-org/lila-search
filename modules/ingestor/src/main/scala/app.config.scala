package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import ciris.*

object AppConfig:

  def load: IO[AppConfig] = appConfig.load[IO]

  def appConfig = (
    MongoConfig.config,
    ElasticConfig.config
  ).parMapN(AppConfig.apply)

case class AppConfig(
    mongo: MongoConfig,
    elastic: ElasticConfig
)
case class MongoConfig(uri: String, name: String)

object MongoConfig:

  private def uri  = env("MONGO_URI").or(prop("mongo.uri")).as[String]
  private def name = env("MONGO_DATABASE").or(prop("mongo.database")).as[String]

  def config = (uri, name).parMapN(MongoConfig.apply)

case class ElasticConfig(uri: String)

object ElasticConfig:
  private def uri = env("ELASTIC_URI").or(prop("elastic.uri")).as[String].default("http://127.0.0.1:9200")
  def config      = uri.map(ElasticConfig.apply)
