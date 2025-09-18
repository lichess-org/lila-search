package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import cats.mtl.Handle
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import lila.search.ingestor.opts.{ IndexOpts, WatchOpts }
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.metrics.Meter

import java.time.Instant

object cli
    extends CommandIOApp(
      name = "lila-search-cli",
      header = "CLI for lila-search",
      version = "3.0.0"
    ):

  given LoggerFactory[IO] = Slf4jFactory.create[IO]
  given Logger[IO] = LoggerFactory[IO].getLogger
  given Meter[IO] = Meter.noop[IO]

  override def main: Opts[IO[ExitCode]] =
    opts.parse.map: opts =>
      makeIngestor.use(execute(opts)).as(ExitCode.Success)

  private def makeIngestor =
    for
      config <- AppConfig.load.toResource
      res <- AppResources.instance(config)
      ingestors <- Ingestors(
        res.lichess,
        res.study,
        res.studyLocal,
        res.store,
        res.elastic,
        config.ingestor
      ).toResource
    yield (ingestors, res.elastic)

  def execute(opts: IndexOpts | WatchOpts)(ingestor: Ingestors, elastic: ESClient[IO]): IO[Unit] =
    opts match
      case opts: IndexOpts => index(ingestor, elastic)(opts)
      case opts: WatchOpts => watch(ingestor)(opts)

  def index(ingestor: Ingestors, elastic: ESClient[IO])(opts: IndexOpts): IO[Unit] =
    putMappingsIfNotExists(elastic, opts.index) *>
      opts.index.match
        case Index.Forum =>
          ingestor.forum.run(opts.since, opts.until, opts.dry)
        case Index.Ublog =>
          ingestor.ublog.run(opts.since, opts.until, opts.dry)
        case Index.Study =>
          ingestor.study.run(opts.since, opts.until, opts.dry)
        case Index.Game =>
          ingestor.game.run(opts.since, opts.until, opts.dry)
        case Index.Team =>
          ingestor.team.run(opts.since, opts.until, opts.dry)
        case _ =>
          ingestor.forum.run(opts.since, opts.until, opts.dry) *>
            ingestor.ublog.run(opts.since, opts.until, opts.dry) *>
            ingestor.study.run(opts.since, opts.until, opts.dry) *>
            ingestor.game.run(opts.since, opts.until, opts.dry) *>
            ingestor.team.run(opts.since, opts.until, opts.dry)

  private def putMappingsIfNotExists(elastic: ESClient[IO], index: Index | Unit): IO[Unit] =
    def go(index: Index) =
      Handle
        .allow:
          elastic
            .indexExists(index)
            .ifM(Logger[IO].info(s"Index ${index.value} exists, start indexing"), elastic.putMapping(index))
        .rescue: e =>
          Logger[IO].error(e.asException)(s"Failed to check or put mapping for ${index.value}") *>
            e.asException.raiseError
    index match
      case i: Index => go(i)
      case _ => Index.values.toList.traverse_(go)

  def watch(ingestor: Ingestors)(opts: WatchOpts): IO[Unit] =
    opts.index match
      case Index.Game =>
        ingestor.game.watch(opts.since.some, opts.dry)
      case Index.Forum =>
        ingestor.forum.watch(opts.since.some, opts.dry)
      case Index.Ublog =>
        ingestor.ublog.watch(opts.since.some, opts.dry)
      case Index.Team =>
        ingestor.team.watch(opts.since.some, opts.dry)
      case Index.Study =>
        ingestor.study.watch(opts.since.some, opts.dry)
      case _ =>
        ingestor.forum.watch(opts.since.some, opts.dry) *>
          ingestor.ublog.watch(opts.since.some, opts.dry) *>
          ingestor.team.watch(opts.since.some, opts.dry) *>
          ingestor.study.watch(opts.since.some, opts.dry) *>
          ingestor.game.watch(opts.since.some, opts.dry)

object opts:
  case class IndexOpts(index: Index | Unit, since: Instant, until: Instant, dry: Boolean)
  case class WatchOpts(index: Index | Unit, since: Instant, dry: Boolean)

  def parse = Opts.subcommand("index", "index documents")(indexOpt) <+>
    Opts.subcommand("watch", "watch change events and index documents")(watchOpt)

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

  val indexOpt = (
    singleIndexOpt orElse allIndexOpt,
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
      .orElse(Instant.now.pure[Opts]),
    dryOpt
  ).mapN(IndexOpts.apply)
    .mapValidated(x =>
      if x.until.isAfter(x.since) then Validated.valid(x)
      else Validated.invalidNel(s"since: ${x.since.toString} must be before until: ${x.until.toString}")
    )

  val watchOpt = (
    singleIndexOpt orElse allIndexOpt,
    Opts
      .option[Instant](
        long = "since",
        help = "Watch all documents since",
        short = "s",
        metavar = "time in epoch seconds"
      )
      .orElse(Instant.now.pure[Opts]),
    dryOpt
  ).mapN(WatchOpts.apply)

  given Argument[Index] =
    Argument.from("index")(x => Validated.fromEither(Index.fromString(x)).toValidatedNel)

  given Argument[Instant] =
    Argument.from("time in epoch seconds"): str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
