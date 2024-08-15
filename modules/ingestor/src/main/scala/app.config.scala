package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import ciris.*

import scala.concurrent.duration.*

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
case class MongoConfig(uri: String, name: String, studyUri: String, studyName: String)

private def studyDatabase =
  env("MONGO_STUDY_DATABASE").or(prop("mongo.study.database")).as[String].default("lichess")

object MongoConfig:

  private def uri  = env("MONGO_URI").or(prop("mongo.uri")).as[String]
  private def name = env("MONGO_DATABASE").or(prop("mongo.database")).as[String].default("lichess")

  private def studyUri = env("MONGO_STUDY_URI").or(prop("mongo.study.uri")).as[String]

  def config = (uri, name, studyUri, studyDatabase).parMapN(MongoConfig.apply)

case class ElasticConfig(uri: String)

object ElasticConfig:
  private def uri = env("ELASTIC_URI").or(prop("elastic.uri")).as[String].default("http://127.0.0.1:9200")
  def config      = uri.map(ElasticConfig.apply)

case class IngestorConfig(forum: IngestorConfig.Forum, team: IngestorConfig.Team, study: IngestorConfig.Study)

object IngestorConfig:
  case class Forum(batchSize: Int, timeWindows: Int, startAt: Option[Long], maxPostLength: Int)
  case class Team(batchSize: Int, timeWindows: Int, startAt: Option[Long])
  case class Study(batchSize: Int, startAt: Option[Long], interval: FiniteDuration, databaseName: String)

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
    private def maxPostLength =
      env("INGESTOR_FORUM_MAX_POST_LENGTH").or(prop("ingestor.forum.max.post.length")).as[Int].default(5_000)
    def config = (batchSize, timeWindows, startAt, maxPostLength).parMapN(Forum.apply)

  private object Team:
    private def batchSize =
      env("INGESTOR_TEAM_BATCH_SIZE").or(prop("ingestor.team.batch.size")).as[Int].default(100)
    private def timeWindows =
      env("INGESTOR_TEAM_TIME_WINDOWS").or(prop("ingestor.team.time.windows")).as[Int].default(10)
    private def startAt =
      env("INGESTOR_TEAM_START_AT").or(prop("ingestor.team.start.at")).as[Long].option
    def config = (batchSize, timeWindows, startAt).mapN(Team.apply)

  private object Study:
    private def batchSize =
      env("INGESTOR_STUDY_BATCH_SIZE").or(prop("ingestor.study.batch.size")).as[Int].default(100)
    private def startAt =
      env("INGESTOR_STUDY_START_AT").or(prop("ingestor.study.start.at")).as[Long].option
    private def interval =
      env("INGESTOR_STUDY_INTERVAL")
        .or(prop("ingestor.study.interval"))
        .as[Long]
        .default(300)
        .map(_.seconds)
    def config = (batchSize, startAt, interval, studyDatabase).mapN(Study.apply)

  def config = (Forum.config, Team.config, Study.config).mapN(IngestorConfig.apply)
