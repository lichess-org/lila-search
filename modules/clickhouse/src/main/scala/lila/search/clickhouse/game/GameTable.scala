package lila.search
package clickhouse.game

import doobie.*

import java.time.Instant

object GameTable:
  // follow best practices: https://clickhouse.com/docs/best-practices/select-data-types
  // - Use Strict Types
  // - Avoid nullable Columns
  // - Minimize Numeric Precision
  // - Optimize Date and Time Precision
  // - Leverage LowCardinality and Specialized Types
  // - Enums for data validation
  val ddl: String = """
    CREATE TABLE IF NOT EXISTS games (
      id           FixedString(8) CODEC(ZSTD(1)),
      status       UInt8 CODEC(ZSTD(1)),
      turns        UInt16 CODEC(T64, ZSTD(1)),
      rated        Bool CODEC(ZSTD(1)),
      perf         UInt8 CODEC(ZSTD(1)),
      winner_color Enum8('unknown'=0, 'white'=1, 'black'=2, 'draw'=3) CODEC(ZSTD(1)),
      date         DateTime CODEC(DoubleDelta, ZSTD(1)),
      analysed     Bool CODEC(ZSTD(1)),
      white_user   String CODEC(ZSTD(3)),
      black_user   String CODEC(ZSTD(3)),
      white_rating UInt16 CODEC(T64, ZSTD(1)),
      black_rating UInt16 CODEC(T64, ZSTD(1)),
      ai_level     UInt8 CODEC(ZSTD(1)),
      duration     UInt16 CODEC(T64, ZSTD(1)),
      clock_init   Int16 CODEC(T64, ZSTD(1)),
      clock_inc    Int16 CODEC(T64, ZSTD(1)),
      source       UInt8 CODEC(ZSTD(1)),
      chess960_pos UInt16 CODEC(T64, ZSTD(1)),
      white_bot    Bool CODEC(ZSTD(1)),
      black_bot    Bool CODEC(ZSTD(1)),
      avg_rating   UInt16 DEFAULT if(white_rating > 0 AND black_rating > 0, toUInt16((white_rating + black_rating) / 2), 0) CODEC(T64, ZSTD(1)),

      INDEX idx_white_user white_user TYPE bloom_filter(0.01) GRANULARITY 1,
      INDEX idx_black_user black_user TYPE bloom_filter(0.01) GRANULARITY 1,
      INDEX idx_avg_rating avg_rating TYPE minmax GRANULARITY 4,
      INDEX idx_perf perf TYPE set(32) GRANULARITY 4,
      INDEX idx_status status TYPE set(32) GRANULARITY 4,
      INDEX idx_ai_level ai_level TYPE minmax GRANULARITY 4,
      INDEX idx_turns turns TYPE minmax GRANULARITY 4,
      INDEX idx_duration duration TYPE minmax GRANULARITY 4
    ) ENGINE = ReplacingMergeTree()
    PARTITION BY toYYYYMM(date)
    ORDER BY (date, id)
  """

  // Unrolled table: each game produces 2 rows (one per player).
  // ORDER BY (user, date, id) enables fast primary-key lookups by username.
  val userDdl: String = """
    CREATE TABLE IF NOT EXISTS games_by_user (
      user            String CODEC(ZSTD(3)),
      date            DateTime CODEC(DoubleDelta, ZSTD(1)),
      id              FixedString(8) CODEC(ZSTD(1)),
      color           UInt8 CODEC(ZSTD(1)),
      opponent        String CODEC(ZSTD(3)),
      perf            UInt8 CODEC(ZSTD(1)),
      rated           Bool CODEC(ZSTD(1)),
      status          UInt8 CODEC(ZSTD(1)),
      turns           UInt16 CODEC(T64, ZSTD(1)),
      winner_color    Enum8('unknown'=0, 'white'=1, 'black'=2, 'draw'=3) CODEC(ZSTD(1)),
      analysed        Bool CODEC(ZSTD(1)),
      rating          UInt16 CODEC(T64, ZSTD(1)),
      opponent_rating UInt16 CODEC(T64, ZSTD(1)),
      ai_level        UInt8 CODEC(ZSTD(1)),
      duration        UInt16 CODEC(T64, ZSTD(1)),
      clock_init      Int16 CODEC(T64, ZSTD(1)),
      clock_inc       Int16 CODEC(T64, ZSTD(1)),
      source          UInt8 CODEC(ZSTD(1)),
      avg_rating      UInt16 CODEC(T64, ZSTD(1))
    ) ENGINE = ReplacingMergeTree()
    PARTITION BY toYYYYMM(date)
    ORDER BY (user, date, id)
  """

  // Two MVs write to the same target table: one for white, one for black.
  // Triggered on every INSERT into games, so games_by_user stays in sync automatically.
  val whiteMvDdl: String = """
    CREATE MATERIALIZED VIEW IF NOT EXISTS games_by_white_mv TO games_by_user AS
    SELECT white_user AS user, date, id, toUInt8(1) AS color,
           black_user AS opponent, perf, rated, status, turns, winner_color,
           analysed, white_rating AS rating, black_rating AS opponent_rating,
           ai_level, duration, clock_init, clock_inc, source,
           if(white_rating > 0 AND black_rating > 0, toUInt16((white_rating + black_rating) / 2), toUInt16(0)) AS avg_rating
    FROM games
  """

  val blackMvDdl: String = """
    CREATE MATERIALIZED VIEW IF NOT EXISTS games_by_black_mv TO games_by_user AS
    SELECT black_user AS user, date, id, toUInt8(2) AS color,
           white_user AS opponent, perf, rated, status, turns, winner_color,
           analysed, black_rating AS rating, white_rating AS opponent_rating,
           ai_level, duration, clock_init, clock_inc, source,
           if(white_rating > 0 AND black_rating > 0, toUInt16((white_rating + black_rating) / 2), toUInt16(0)) AS avg_rating
    FROM games
  """

  def create: ConnectionIO[Int] = Fragment.const(ddl).update.run

  def createAll: ConnectionIO[Unit] =
    for
      _ <- Fragment.const(ddl).update.run
      _ <- Fragment.const(userDdl).update.run
      _ <- Fragment.const(whiteMvDdl).update.run
      _ <- Fragment.const(blackMvDdl).update.run
    yield ()

case class GameRow(
    id: String,
    status: Int,
    turns: Int,
    rated: Boolean,
    perf: Int,
    winnerColor: WinnerColor,
    date: Instant,
    analysed: Boolean,
    whiteRating: Int,
    blackRating: Int,
    aiLevel: Int, // stockfish level from 1 to 8, 0 means no stockfish
    duration: Int,
    // clockInit has max value of 3 hours mean 10800 seconds
    clockInit: Int,
    // probably has max value of 180 seconds
    clockInc: Int,
    whiteUser: String,
    blackUser: String,
    // 0 mean no source: https://github.com/lichess-org/lila/blob/fd3a5787186eb5b763731e83b99bd82427bad48c/modules/core/src/main/game/misc.scala#L60
    source: Int,
    // chess960 has 960 positions from 0 to 959, 1000 means it's not a chess960 game
    chess960Position: Int,
    whiteBot: Boolean,
    blackBot: Boolean
)

enum WinnerColor(val value: Int):
  case Unknown extends WinnerColor(0)
  case White extends WinnerColor(1)
  case Black extends WinnerColor(2)
  case Draw extends WinnerColor(3)

object WinnerColor:
  def fromInt(v: Int): WinnerColor = v match
    case 1 => WinnerColor.White
    case 2 => WinnerColor.Black
    case 3 => WinnerColor.Draw
    case _ => WinnerColor.Unknown

object GameRow:
  // ClickHouse DateTime maps to java.sql.Timestamp via JDBC.
  given Meta[Instant] =
    Meta[java.sql.Timestamp].timap(_.toInstant)(java.sql.Timestamp.from)

  given Meta[WinnerColor] =
    Meta[Int].timap(WinnerColor.fromInt)(_.value)

  // format: off
  given Write[GameRow] =
    Write[( String, Int, Int, Boolean, Int, WinnerColor, Instant, Boolean, Int, Int, Int, Int, Int, Int, String, String, Int, Int, Boolean, Boolean)]
      .contramap: r =>
        (r.id, r.status, r.turns, r.rated, r.perf, r.winnerColor, r.date, r.analysed, r.whiteRating, r.blackRating, r.aiLevel, r.duration, r.clockInit, r.clockInc, r.whiteUser, r.blackUser, r.source, r.chess960Position, r.whiteBot, r.blackBot)
