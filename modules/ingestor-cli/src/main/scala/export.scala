package lila.search
package ingestor

import cats.effect.IO
import fs2.data.csv.*
import fs2.data.csv.generic.semiauto.*
import fs2.io.file.{ Files, Path }
import lila.search.ingestor.opts.ExportOpts
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.metrics.MeterProvider

object Export:

  def games(opts: ExportOpts)(using LoggerFactory[IO]): IO[Unit] =
    given MeterProvider[IO] = MeterProvider.noop[IO]
    AppConfig.load.flatMap: config =>
      AppResources.instance(config).use: res =>
        GameRepo(res.lichess, config.ingestor.game).flatMap: repo =>
          repo
            .fetch(opts.since, opts.until)
            .flatMap: result =>
              fs2.Stream.emits(result.toIndex)
            .map { case (id, game) =>
              GameCsv.fromDbGame(id, game)
            }
            .through(encodeUsingFirstHeaders(fullRows = true))
            .intersperse("\n")
            .through(fs2.text.utf8.encode)
            .through(Files[IO].writeAll(Path(opts.output)))
            .compile
            .drain

// CSV representation of GameSource for export
case class GameCsv(
    id: String,
    status: Int,
    turns: Int,
    rated: Boolean,
    perf: Int,
    winnerColor: Int,
    date: Long,
    analysed: Boolean,
    uids: String,
    winner: String,
    loser: String,
    averageRating: String,
    ai: String,
    duration: String,
    clockInit: String,
    clockInc: String,
    whiteUser: String,
    blackUser: String,
    source: String
)

object GameCsv:
  given CsvRowEncoder[GameCsv, String] = deriveCsvRowEncoder

  def fromDbGame(id: String, game: DbGame): GameCsv =
    GameCsv(
      id = id,
      status = game.status,
      turns = (game.ply + 1) / 2,
      rated = game.rated.getOrElse(false),
      perf = perfId(game.variantOrDefault, game.speed),
      winnerColor = game.winnerColor.fold(3)(if _ then 1 else 2),
      date = game.movedAt.toEpochMilli,
      analysed = game.analysed.getOrElse(false),
      uids = game.players.mkString(","),
      winner = game.winnerId.getOrElse(""),
      loser = game.loser.getOrElse(""),
      averageRating = averageUsersRating(game).fold("")(_.toString),
      ai = game.aiLevel.fold("")(_.toString),
      duration = durationSeconds(game).fold("")(_.toString),
      clockInit = game.clockInit.fold("")(_.toString),
      clockInc = game.clockInc.fold("")(_.toString),
      whiteUser = game.whiteId.getOrElse(""),
      blackUser = game.blackId.getOrElse(""),
      source = game.source.fold("")(_.toString)
    )

  private def averageUsersRating(g: DbGame): Option[Int] =
    List(g.whitePlayer.flatMap(_.rating), g.blackPlayer.flatMap(_.rating)).flatten match
      case a :: b :: Nil => Some((a + b) / 2)
      case a :: Nil      => Some((a + 1500) / 2)
      case _             => None

  private def durationSeconds(g: DbGame): Option[Int] =
    val seconds = (g.movedAt.toEpochMilli / 1000 - g.createdAt.toEpochMilli / 1000)
    Option.when(seconds < 60 * 60 * 12)(seconds.toInt)

  private def perfId(variant: chess.variant.Variant, speed: chess.Speed): Int =
    import chess.variant.*
    variant.match
      case Standard | FromPosition =>
        speed match
          case chess.Speed.UltraBullet    => 0
          case chess.Speed.Bullet         => 1
          case chess.Speed.Blitz          => 2
          case chess.Speed.Rapid          => 6
          case chess.Speed.Classical      => 3
          case chess.Speed.Correspondence => 4
      case Crazyhouse    => 18
      case Chess960      => 11
      case KingOfTheHill => 12
      case ThreeCheck    => 15
      case Antichess     => 13
      case Atomic        => 14
      case Horde         => 16
      case RacingKings   => 17
