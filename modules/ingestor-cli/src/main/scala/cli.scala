package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import cats.mtl.Handle
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
      forum <- ForumRepo(res.lichess, config.ingestor.forum).toResource
      ublog <- UblogRepo(res.lichess, config.ingestor.ublog).toResource
      study <- StudyRepo(res.study, res.studyLocal, config.ingestor.study).toResource
      game <- GameRepo(res.lichess, config.ingestor.game).toResource
      team <- TeamRepo(res.lichess, config.ingestor.team).toResource
    yield (forum, ublog, study, game, team, res)

  def execute(opts: IndexOpts | ExportOpts)(
      forumRepo: Repo[DbForum],
      ublogRepo: Repo[DbUblog],
      studyRepo: Repo[(DbStudy, StudyChapterData)],
      gameRepo: Repo[DbGame],
      teamRepo: Repo[DbTeam],
      resources: AppResources
  ): IO[Unit] =
    opts match
      case opts: IndexOpts =>
        run(forumRepo, ublogRepo, studyRepo, gameRepo, teamRepo, resources.store, resources.elastic)(opts)
      case opts: ExportOpts => `export`(gameRepo, opts)

  def run(
      forumRepo: Repo[DbForum],
      ublogRepo: Repo[DbUblog],
      studyRepo: Repo[(DbStudy, StudyChapterData)],
      gameRepo: Repo[DbGame],
      teamRepo: Repo[DbTeam],
      store: KVStore,
      elastic: ESClient[IO]
  )(opts: IndexOpts): IO[Unit] =
    val ingestors =
      if opts.watch then
        new Ingestors(
          Ingestor.watch(Index.Forum, forumRepo, Translate.forum, store, elastic, opts.since.some, opts.dry),
          Ingestor.watch(Index.Ublog, ublogRepo, Translate.ublog, store, elastic, opts.since.some, opts.dry),
          Ingestor
            .watch(Index.Study, studyRepo, Translate.study.tupled, store, elastic, opts.since.some, opts.dry),
          Ingestor.watch(Index.Game, gameRepo, Translate.game, store, elastic, opts.since.some, opts.dry),
          Ingestor.watch(Index.Team, teamRepo, Translate.team, store, elastic, opts.since.some, opts.dry)
        )
      else
        new Ingestors(
          Ingestor
            .index(Index.Forum, forumRepo, Translate.forum, store, elastic, opts.since, opts.until, opts.dry),
          Ingestor
            .index(Index.Ublog, ublogRepo, Translate.ublog, store, elastic, opts.since, opts.until, opts.dry),
          Ingestor.index(
            Index.Study,
            studyRepo,
            Translate.study.tupled,
            store,
            elastic,
            opts.since,
            opts.until,
            opts.dry
          ),
          Ingestor
            .index(Index.Game, gameRepo, Translate.game, store, elastic, opts.since, opts.until, opts.dry),
          Ingestor.index(
            Index.Team,
            teamRepo,
            Translate.team,
            store,
            elastic,
            opts.since,
            opts.until,
            opts.dry
          )
        )
    index(ingestors, elastic, opts)

  private def index(
      ingestors: Ingestors,
      elastic: ESClient[IO],
      opts: IndexOpts
  ): IO[Unit] =
    putMappingsIfNotExists(elastic, opts.index) *>
      opts.index.match
        case Index.Forum => ingestors.forum.run()
        case Index.Ublog => ingestors.ublog.run()
        case Index.Study => ingestors.study.run()
        case Index.Game => ingestors.game.run()
        case Index.Team => ingestors.team.run()
        case _ => ingestors.run()
      *> refreshIndexes(elastic, opts.index).whenA(opts.refresh)

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

  private def refreshIndexes(elastic: ESClient[IO], index: Index | Unit): IO[Unit] =
    def go(index: Index) =
      Handle
        .allow:
          elastic
            .refreshIndex(index)
        .rescue: e =>
          Logger[IO].error(e.asException)(s"Failed to refresh index: ${index.value}") *>
            e.asException.raiseError
    index.match
      case i: Index => go(i)
      case _ => Index.values.toList.traverse_(go)

  def `export`(repo: Repo[DbGame], opts: ExportOpts): IO[Unit] =
    val mode = if opts.watch then "watch mode" else s"from ${opts.since.toString} to ${opts.until.toString}"
    Logger[IO].info(s"Exporting ${opts.index.value} $mode to ${opts.output}") *>
      (opts.index match
        case Index.Game => exportGames(repo, opts)
        case _ =>
          IO.raiseError(new UnsupportedOperationException(s"Export not supported for ${opts.index.value}")))

  private def exportGames(repo: Repo[DbGame], opts: ExportOpts): IO[Unit] =
    val ingestor =
      if opts.watch then
        CsvExport.watch(repo, GameCsv.fromDbGame, CsvSink[GameCsv](opts.output), opts.since.some)
      else CsvExport(repo, GameCsv.fromDbGame, CsvSink[GameCsv](opts.output), opts.since, opts.until)
    ingestor.run()

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
    Opts
      .option[String](
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
    Argument.from("index")(x => Validated.fromEither(Index.fromString(x)).toValidatedNel)

  given Argument[Instant] =
    Argument.from("time in epoch seconds"): str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
