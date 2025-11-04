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
      repos <- (
        ForumRepo(res.lichess, config.ingestor.forum),
        UblogRepo(res.lichess, config.ingestor.ublog),
        StudyRepo(res.study, res.studyLocal, config.ingestor.study),
        GameRepo(res.lichess, config.ingestor.game),
        TeamRepo(res.lichess, config.ingestor.team)
      ).mapN((_, _, _, _, _)).toResource
    yield (repos, res.elastic)

  def execute(opts: IndexOpts | ExportOpts)(
      repos: (
          Repo[DbForum],
          Repo[DbUblog],
          Repo[(DbStudy, StudyChapterData)],
          Repo[DbGame],
          Repo[DbTeam]
      ),
      elastic: ESClient[IO]
  ): IO[Unit] =
    val (forumRepo, ublogRepo, studyRepo, gameRepo, teamRepo) = repos
    opts match
      case opts: IndexOpts =>
        index(forumRepo, ublogRepo, studyRepo, gameRepo, teamRepo, elastic)(opts)
      case opts: ExportOpts => `export`(opts)

  def index(
      forumRepo: Repo[DbForum],
      ublogRepo: Repo[DbUblog],
      studyRepo: Repo[(DbStudy, StudyChapterData)],
      gameRepo: Repo[DbGame],
      teamRepo: Repo[DbTeam],
      elastic: ESClient[IO]
  )(opts: IndexOpts): IO[Unit] =
    if opts.watch then indexWatch(forumRepo, ublogRepo, studyRepo, gameRepo, teamRepo, elastic, opts)
    else indexBatch(forumRepo, ublogRepo, studyRepo, gameRepo, teamRepo, elastic, opts)

  private def indexBatch(
      forumRepo: Repo[DbForum],
      ublogRepo: Repo[DbUblog],
      studyRepo: Repo[(DbStudy, StudyChapterData)],
      gameRepo: Repo[DbGame],
      teamRepo: Repo[DbTeam],
      elastic: ESClient[IO],
      store: KVStore,
      opts: IndexOpts
  ): IO[Unit] =
    putMappingsIfNotExists(elastic, opts.index) *>
      opts.index.match
        case Index.Forum =>
          Ingestor
            .index(
              Index.Forum,
              forumRepo,
              Translate.forum,
              store,
              elastic,
              opts.since,
              opts.until,
              opts.dry
            )
            .run()
        case Index.Ublog =>
          Ingestor
            .index(Index.Ublog, ublogRepo, Translate.ublog, elastic, opts.since, opts.until, opts.dry)
            .run()
        case Index.Study =>
          Ingestor
            .index(
              Index.Study,
              studyRepo,
              Translate.study.tupled,
              store,
              elastic,
              opts.since,
              opts.until,
              opts.dry
            )
            .run()
        case Index.Game =>
          Ingestor
            .index(Index.Game, gameRepo, Translate.game, store, elastic, opts.since, opts.until, opts.dry)
            .run()
        case Index.Team =>
          Ingestor
            .index(Index.Team, teamRepo, Translate.team, store, elastic, opts.since, opts.until, opts.dry)
            .run()
        case _ =>
          Ingestor
            .index(
              Index.Forum,
              forumRepo,
              Translate.forum,
              store,
              elastic,
              opts.since,
              opts.until,
              opts.dry
            )
            .run() *>
            Ingestor
              .index(
                Index.Ublog,
                ublogRepo,
                Translate.ublog,
                store,
                elastic,
                opts.since,
                opts.until,
                opts.dry
              )
              .run() *>
            Ingestor
              .index(
                Index.Study,
                studyRepo,
                Translate.study.tupled,
                store,
                elastic,
                opts.since,
                opts.until,
                opts.dry
              )
              .run() *>
            Ingestor
              .index(Index.Game, gameRepo, Translate.game, store, elastic, opts.since, opts.until, opts.dry)
              .run() *>
            Ingestor
              .index(Index.Team, teamRepo, Translate.team, store, elastic, opts.since, opts.until, opts.dry)
              .run()
      *> refreshIndexes(elastic, opts.index).whenA(opts.refresh)

  private def indexWatch(
      forumRepo: Repo[DbForum],
      ublogRepo: Repo[DbUblog],
      studyRepo: Repo[(DbStudy, StudyChapterData)],
      gameRepo: Repo[DbGame],
      teamRepo: Repo[DbTeam],
      store: KVStore,
      elastic: ESClient[IO],
      opts: IndexOpts
  ): IO[Unit] =
    opts.index match
      case Index.Game =>
        Ingestor.watch(Index.Game, gameRepo, Translate.game, store, elastic, opts.since.some, opts.dry).run()
      case Index.Forum =>
        Ingestor
          .watch(Index.Forum, forumRepo, Translate.forum, store, elastic, opts.since.some, opts.dry)
          .run()
      case Index.Ublog =>
        Ingestor
          .watch(Index.Ublog, ublogRepo, Translate.ublog, store, elastic, opts.since.some, opts.dry)
          .run()
      case Index.Team =>
        Ingestor.watch(Index.Team, teamRepo, Translate.team, store, elastic, opts.since.some, opts.dry).run()
      case Index.Study =>
        Ingestor
          .watch(Index.Study, studyRepo, Translate.study.tupled, store, elastic, opts.since.some, opts.dry)
          .run()
      case _ =>
        Ingestor
          .watch(Index.Forum, forumRepo, Translate.forum, store, elastic, opts.since.some, opts.dry)
          .run() *>
          Ingestor
            .watch(Index.Ublog, ublogRepo, Translate.ublog, store, elastic, opts.since.some, opts.dry)
            .run() *>
          Ingestor
            .watch(Index.Team, teamRepo, Translate.team, store, elastic, opts.since.some, opts.dry)
            .run() *>
          Ingestor
            .watch(Index.Study, studyRepo, Translate.study.tupled, store, elastic, opts.since.some, opts.dry)
            .run() *>
          Ingestor
            .watch(Index.Game, gameRepo, Translate.game, store, elastic, opts.since.some, opts.dry)
            .run()

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
          Logger[IO].error(e.asException)(s"Failed to check or put mapping for ${index.value}") *>
            e.asException.raiseError
    index.match
      case i: Index => go(i)
      case _ => Index.values.toList.traverse_(go)

  def `export`(opts: ExportOpts): IO[Unit] =
    val mode = if opts.watch then "watch mode" else s"from ${opts.since.toString} to ${opts.until.toString}"
    Logger[IO].info(s"Exporting ${opts.index.value} $mode to ${opts.output}") *>
      (opts.index match
        case Index.Game => exportGames(opts)
        case _ =>
          IO.raiseError(new UnsupportedOperationException(s"Export not supported for ${opts.index.value}")))

  private def exportGames(opts: ExportOpts): IO[Unit] =
    AppConfig.load.flatMap: config =>
      AppResources
        .instance(config)
        .use: res =>
          GameRepo(res.lichess, config.ingestor.game).flatMap: repo =>
            if opts.watch then
              Exporter(repo, GameCsv.fromDbGame, CsvSink[GameCsv](opts.output))
                .watch(opts.since.some)
            else
              Exporter(repo, GameCsv.fromDbGame, CsvSink[GameCsv](opts.output))
                .run(opts.since, opts.until)

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
