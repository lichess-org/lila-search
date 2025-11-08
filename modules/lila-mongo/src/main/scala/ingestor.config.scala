package lila.search
package ingestor

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

case class IngestorConfig(
    forum: IngestorConfig.Forum,
    ublog: IngestorConfig.Ublog,
    team: IngestorConfig.Team,
    study: IngestorConfig.Study,
    game: IngestorConfig.Game
)

object IngestorConfig:
  case class Forum(batchSize: Int, timeWindows: Int, startAt: Option[Instant], maxPostLength: Int)
  case class Ublog(batchSize: Int, timeWindows: Int, startAt: Option[Instant])
  case class Team(batchSize: Int, timeWindows: Int, startAt: Option[Instant])
  case class Study(batchSize: Int, startAt: Option[Instant], interval: FiniteDuration, databaseName: String)
  case class Game(batchSize: Int, timeWindows: Int, startAt: Option[Instant])
