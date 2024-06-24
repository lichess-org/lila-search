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

case class IngestorConfig(forum: IngestorConfig.Forum, team: IngestorConfig.Team, study: IngestorConfig.Study)

object IngestorConfig:
  case class Forum(batchSize: Int, timeWindows: Int, startAt: Option[Long])
  case class Team(batchSize: Int, timeWindows: Int, startAt: Option[Long])
  case class Study(batchSize: Int, timeWindows: Int, startAt: Option[Long])

  private object Forum:
    private def batchSize =
      env("INGESTOR_FORUM_BATCH_SIZE").or(prop("ingestor.forum.batch.size")).as[Int].default(100)
    private def timeWindows =
      env("INGESTOR_FORUM_TIME_WINDOWS").or(prop("ingestor.forum.time.windows")).as[Int].default(10)
    private def startAt =
      env("INGESTOR_FORUM_START_AT")
        .or(prop("ingestor.forum.start.at"))
        .as[Long]
        .option
    def config = (batchSize, timeWindows, startAt).parMapN(Forum.apply)

  private object Team:
    private def batchSize =
      env("INGESTOR_TEAM_BATCH_SIZE").or(prop("ingestor.team.batch.size")).as[Int].default(100)
    private def timeWindows =
      env("INGESTOR_TEAM_TIME_WINDOWS").or(prop("ingestor.team.time.windows")).as[Int].default(10)
    private def startAt =
      env("INGESTOR_TEAM_START_AT").or(prop("ingestor.team.start.at")).as[Long].option
    def config = (batchSize, timeWindows, startAt).parMapN(Team.apply)

  private object Study:
    private def batchSize =
      env("INGESTOR_STUDY_BATCH_SIZE").or(prop("ingestor.study.batch.size")).as[Int].default(100)
    private def timeWindows =
      env("INGESTOR_STUDY_TIME_WINDOWS").or(prop("ingestor.study.time.windows")).as[Int].default(10)
    private def startAt =
      env("INGESTOR_STUDY_START_AT").or(prop("ingestor.study.start.at")).as[Long].option
    def config = (batchSize, timeWindows, startAt).parMapN(Study.apply)

  def config = (Forum.config, Team.config, Study.config).parMapN(IngestorConfig.apply)
