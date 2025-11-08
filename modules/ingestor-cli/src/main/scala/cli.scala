package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import lila.search.ingestor.opts.{ ExportOpts, IndexOpts }
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.metrics.MeterProvider

import java.time.Instant

object cli
    extends CommandIOApp(
      name = "lila-search-cli",
      header = "CLI for lila-search",
      version = "3.0.0"
    ):

  given LoggerFactory[IO] = Slf4jFactory.create[IO]
  given Logger[IO] = LoggerFactory[IO].getLogger
  given MeterProvider[IO] = MeterProvider.noop[IO]

  override def main: Opts[IO[ExitCode]] =
    opts.parse.map: opts =>
      Logger[IO].info(s"Starting lila-search-cli with ${opts.toString}") *>
        makeResources.use(execute(opts)).as(ExitCode.Success)

  private def makeResources =
    for
      config <- AppConfig.load.toResource
      res <- AppResources.instance(config)
    yield (res, config)

  def execute(opts: IndexOpts | ExportOpts)(resources: AppResources, config: AppConfig): IO[Unit] =
    opts match
      case opts: IndexOpts => Indexer(opts, resources, config)
      case opts: ExportOpts => CsvExport(opts, config.mongo.lichess, config.ingestor.game)

object opts:
  case class IndexOpts(
      index: Index | Unit,
      since: Instant,
      until: Instant,
      refresh: Boolean,
      dry: Boolean,
      watch: Boolean
  )

  case class ExportOpts(
      index: Index,
      format: String,
      output: String,
      since: Instant,
      until: Instant,
      watch: Boolean
  )

  def parse = Opts.subcommand("index", "index documents")(indexOpt) <+>
    Opts.subcommand("export", "export documents to file")(exportOpt)

  val singleIndexOpt =
    Opts
      .option[Index](
        long = "index",
        help = "Target index",
        short = "i",
        metavar = "forum|ublog|team|study|game"
      )

  val allIndexOpt =
    Opts
      .flag(long = "all", help = "All indexes")
      .void

  val dryOpt =
    Opts
      .flag(long = "dry", help = "Dry run", short = "d")
      .orNone
      .map(_.isDefined)

  val refreshOpt =
    Opts
      .flag(long = "refresh", help = "Refresh index(ex) after finishing index", short = "r")
      .orNone
      .map(_.isDefined)

  val watchOpt =
    Opts
      .flag(long = "watch", help = "Watch change events and continuously index", short = "w")
      .orNone
      .map(_.isDefined)

  val untilOpt =
    Opts
      .option[Instant](
        long = "until",
        help = "optional upper bound time",
        short = "u",
        metavar = "time in epoch seconds"
      )

  val sinceOpt =
    Opts.option[Instant](
      long = "since",
      help = "Index all documents since",
      short = "s",
      metavar = "time in epoch seconds"
    )

  val indexOpt = (
    singleIndexOpt.orElse(allIndexOpt),
    sinceOpt,
    untilOpt.orElse(Instant.now.pure[Opts]),
    refreshOpt,
    dryOpt,
    watchOpt
  ).mapN(IndexOpts.apply)
    .mapValidated(x =>
      if x.watch || x.until.isAfter(x.since) then Validated.valid(x)
      else Validated.invalidNel(s"since: ${x.since.toString} must be before until: ${x.until.toString}")
    )

  val exportOpt = (
    singleIndexOpt,
    Opts
      .option[String](
        long = "format",
        help = "Export format (csv)",
        short = "f",
        metavar = "csv"
      )
      .withDefault("csv"),
    Opts.option[String](
      long = "output",
      help = "Output file path",
      short = "o",
      metavar = "path/to/file.csv"
    ),
    sinceOpt,
    untilOpt.orElse(Instant.now.pure[Opts]),
    watchOpt
  ).mapN(ExportOpts.apply)
    .mapValidated: x =>
      if x.watch || x.until.isAfter(x.since) then Validated.valid(x)
      else Validated.invalidNel(s"since: ${x.since.toString} must be before until: ${x.until.toString}")

  given Argument[Index] =
    Argument.from("index")(Index.fromString(_).toValidatedNel)

  given Argument[Instant] =
    Argument.from("time in epoch seconds"): str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
