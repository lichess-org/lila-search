package lila.search
package game.csv

import cats.data.Validated
import cats.effect.*
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import lila.search.ingestor.{ IngestorConfig, MongoDatabaseConfig }
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

object cli
    extends CommandIOApp(
      name = "lila-game-export",
      header = "CLI for exporting lichess games from MongoDB",
      version = "3.0.0"
    ):

  given LoggerFactory[IO] = Slf4jFactory.create[IO]
  given Logger[IO] = LoggerFactory[IO].getLogger

  override def main: Opts[IO[ExitCode]] =
    opts.parse.map: opts =>
      execute(opts).as(ExitCode.Success)

  def execute(opts: ExportOpts)(using LoggerFactory[IO]): IO[Unit] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    val mongoConfig = MongoDatabaseConfig(
      uri = opts.mongoUri,
      name = opts.mongoDatabase
    )
    val ingestorConfig = IngestorConfig.Game(
      batchSize = opts.batchSize,
      timeWindows = opts.timeWindows,
      startAt = opts.since.some
    )
    Logger[IO].info(s"Starting game export from ${opts.mongoUri}/${opts.mongoDatabase}") *>
      Logger[IO].info(s"Output: ${opts.output}, Since: ${opts.since.toString}, Watch: ${opts.watch}") *>
      GameExport(
        mongoConfig,
        ingestorConfig,
        opts.output,
        opts.since,
        opts.until,
        opts.watch
      )

case class ExportOpts(
    mongoUri: String,
    mongoDatabase: String,
    batchSize: Int,
    timeWindows: Int,
    format: String,
    output: String,
    since: Instant,
    until: Instant,
    watch: Boolean
)

object opts:

  def parse: Opts[ExportOpts] =
    (
      mongoUriOpt,
      mongoDatabaseOpt,
      batchSizeOpt,
      timeWindowsOpt,
      formatOpt,
      outputOpt,
      sinceOpt,
      untilOpt,
      watchOpt
    ).mapN(ExportOpts.apply)
      .mapValidated: x =>
        if x.watch || x.until.isAfter(x.since) then Validated.valid(x)
        else Validated.invalidNel(s"since: ${x.since.toString} must be before until: ${x.until.toString}")

  private val mongoUriOpt =
    Opts
      .option[String](
        long = "mongo-uri",
        help = "MongoDB connection URI (can also use MONGO_URI env var)",
        metavar = "mongodb://host:port"
      )
      .orElse(Opts.env[String]("MONGO_URI", help = "MongoDB URI from environment"))
      .withDefault("mongodb://localhost:27017")

  private val mongoDatabaseOpt =
    Opts
      .option[String](
        long = "mongo-database",
        help = "MongoDB database name (can also use MONGO_DATABASE env var)",
        metavar = "database"
      )
      .orElse(Opts.env[String]("MONGO_DATABASE", help = "MongoDB database from environment"))
      .withDefault("lichess")

  private val batchSizeOpt =
    Opts
      .option[Int](
        long = "batch-size",
        help = "Batch size for MongoDB operations",
        metavar = "size"
      )
      .withDefault(1000)

  private val timeWindowsOpt =
    Opts
      .option[Int](
        long = "time-windows",
        help = "Time window in seconds for batching change stream events",
        metavar = "seconds"
      )
      .withDefault(5)

  private val formatOpt =
    Opts
      .option[String](
        long = "format",
        help = "Export format (currently only 'csv' is supported)",
        short = "f",
        metavar = "csv"
      )
      .withDefault("csv")

  private val outputOpt =
    Opts.option[String](
      long = "output",
      help = "Output file path",
      short = "o",
      metavar = "path/to/file.csv"
    )

  private val sinceOpt =
    Opts.option[Instant](
      long = "since",
      help = "Export all games since this time",
      short = "s",
      metavar = "time in epoch seconds"
    )

  private val untilOpt =
    Opts
      .option[Instant](
        long = "until",
        help = "Optional upper bound time (ignored if --watch is set)",
        short = "u",
        metavar = "time in epoch seconds"
      )
      .orElse(Instant.now.pure[Opts])

  private val watchOpt =
    Opts
      .flag(long = "watch", help = "Watch change events and continuously export", short = "w")
      .orNone
      .map(_.isDefined)

  given Argument[Instant] =
    Argument.from("time in epoch seconds"): str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
