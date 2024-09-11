package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import lila.search.ingestor.opts.{ IndexOpts, WatchOpts }
import org.typelevel.log4cats.slf4j.{ Slf4jFactory, Slf4jLogger }
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

object cli
    extends CommandIOApp(
      name = "lila-search-cli",
      header = "CLI for lila-search",
      version = "3.0.0"
    ):

  given Logger[IO]        = Slf4jLogger.getLogger[IO]
  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  override def main: Opts[IO[ExitCode]] =
    opts.parse.map: opts =>
      makeExecutor.use(_.execute(opts).as(ExitCode.Success))

  def makeExecutor: Resource[IO, Executor] =
    for
      config <- AppConfig.load.toResource
      res    <- AppResources.instance(config)
      forum  <- ForumIngestor(res.lichess, res.elastic, res.store, config.ingestor.forum).toResource
      team   <- TeamIngestor(res.lichess, res.elastic, res.store, config.ingestor.team).toResource
      study <- StudyIngestor(
        res.study,
        res.studyLocal,
        res.elastic,
        res.store,
        config.ingestor.study
      ).toResource
      game <- GameIngestor(res.lichess, res.elastic, res.store, config.ingestor.game).toResource
    yield Executor(forum, study, game, team)

  class Executor(
      val forum: ForumIngestor,
      val study: StudyIngestor,
      val game: GameIngestor,
      val team: TeamIngestor
  ):
    def execute(opts: IndexOpts | WatchOpts): IO[Unit] =
      opts match
        case opts: IndexOpts => index(opts)
        case opts: WatchOpts => watch(opts)

    def index(opts: IndexOpts): IO[Unit] =
      opts.index match
        case Index.Forum =>
          forum.run(opts.since, opts.until, opts.dry).compile.drain
        case Index.Study =>
          study.run(opts.since, opts.until, opts.dry).compile.drain
        case Index.Game =>
          game.run(opts.since, opts.until, opts.dry).compile.drain
        case Index.Team =>
          team.run(opts.since, opts.until, opts.dry).compile.drain

    def watch(opts: WatchOpts): IO[Unit] =
      opts.index match
        case Index.Game =>
          game.watch(opts.since.some, opts.dry).compile.drain
        case _ => IO.println("We only support game watch for now")

object opts:
  case class IndexOpts(index: Index, since: Instant, until: Instant, dry: Boolean)
  case class WatchOpts(index: Index, since: Instant, dry: Boolean)

  def parse = Opts.subcommand("index", "index documents")(indexOpt) <+>
    Opts.subcommand("watch", "watch change events and index documents")(watchOpt)

  val indexOpt = (
    Opts.option[Index](
      long = "index",
      help = "Target index",
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
      .orElse(Instant.now.pure[Opts]),
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
      if x.until.isAfter(x.since) then Validated.valid(x)
      else Validated.invalidNel(s"since: ${x.since} must be before until: ${x.until}")
    )

  val watchOpt = (
    Opts.option[Index](
      long = "index",
      help = "Target index (only `game` for now)",
      short = "i",
      metavar = "forum|team|study|game"
    ),
    Opts
      .option[Instant](
        long = "since",
        help = "Watch all documents since",
        short = "s",
        metavar = "time in epoch seconds"
      )
      .orElse(Instant.now.pure[Opts]),
    Opts
      .flag(
        long = "dry",
        help = "Dry run",
        short = "d"
      )
      .orNone
      .map(_.isDefined)
  ).mapN(WatchOpts.apply)

  given Argument[Index] =
    Argument.from("index")(x => Validated.fromEither(Index.fromString(x)).toValidatedNel)

  given Argument[Instant] =
    Argument.from("time in epoch seconds"): str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
