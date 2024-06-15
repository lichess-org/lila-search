package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import ciris.*

object AppConfig:

  def load: IO[AppConfig] = appConfig.load[IO]

  def appConfig = (
    MongoConfig.config,
    ElasticConfig.config,
    IngestorConfig.config
  ).parMapN(AppConfig.apply)

case class AppConfig(
    mongo: MongoConfig,
    elastic: ElasticConfig,
    ingestor: IngestorConfig
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

case class IngestorConfig(forum: IngestorConfig.Config)

object IngestorConfig:
  case class Config(batchSize: Int, maxBodyLength: Int, timeWindows: Int)

  object forum:
    private def batchSize =
      env("INGESTOR_FORUM_BATCH_SIZE").or(prop("ingestor.forum.batch.size")).as[Int].default(100)
    private def maxBodyLength =
      env("INGESTOR_FORUM_MAX_BODY_LENGTH").or(prop("ingestor.forum.max.body.length")).as[Int].default(10000)
    private def timeWindows =
      env("INGESTOR_FORUM_TIME_WINDOWS").or(prop("ingestor.forum.time.windows")).as[Int].default(10)
    def config = (batchSize, maxBodyLength, timeWindows).parMapN(Config.apply)

  def config = forum.config.map(IngestorConfig.apply)
