package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import lila.search.ingestor.opts.IndexOpts
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant

// TODO: support flags: dry, verbose
object cli
    extends CommandIOApp(
      name = "lila-search-cli",
      header = "CLI for lila-search",
      version = "3.0.0"
    ):

  given Logger[IO] = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] =
    opts.parse.map(x => execute(x).as(ExitCode.Success))

  def makeIngestor: Resource[IO, ForumIngestor] =
    for
      config <- AppConfig.load.toResource
      res    <- AppResources.instance(config)
      forum  <- ForumIngestor(res.mongo, res.elastic, res.store, config.ingestor.forum).toResource
    yield forum

  def execute(opts: IndexOpts): IO[Unit] =
    if opts.index == Index.Forum then makeIngestor.use(_.run(opts.since, opts.until, opts.dry).compile.drain)
    else IO.println("We only support forum backfill for now")

object opts:
  case class IndexOpts(index: Index, since: Instant, until: Option[Instant], dry: Boolean)

  def parse = Opts.subcommand("index", "index documents")(indexOpt)

  val indexOpt = (
    Opts.option[Index](
      long = "index",
      help = "Index to index (only `forum` for now)",
      short = "i",
      metavar = "forum|team|study|game"
    ),
    Opts.option[Instant](
      long = "since",
      help = "Index all documents since",
      short = "s",
      metavar = "time in epoch seconds"
    ),
    Opts
      .option[Instant](
        long = "until",
        help = "optional upper bound time",
        short = "u",
        metavar = "time in epoch seconds"
      )
      .orNone,
    Opts
      .flag(
        long = "dry",
        help = "Dry run",
        short = "d"
      )
      .orNone
      .map(_.isDefined)
  ).mapN(IndexOpts.apply)
    .mapValidated(x =>
      if x.until.forall(_.isAfter(x.since)) then Validated.valid(x)
      else Validated.invalidNel(s"since: ${x.since} must be before until: ${x.until}")
    )

  given Argument[Index] =
    Argument.from("index")(x => Validated.fromEither(Index.fromString(x)).toValidatedNel)

  given Argument[Instant] =
    Argument.from("time in epoch seconds"): str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
