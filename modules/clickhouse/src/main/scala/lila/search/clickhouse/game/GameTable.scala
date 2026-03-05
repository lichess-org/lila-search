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
      id           String CODEC(LZ4),
      status       UInt8 CODEC(ZSTD(1)),
      turns        UInt16 CODEC(ZSTD(1)),
      rated        Bool CODEC(ZSTD(1)),
      perf         UInt8 CODEC(ZSTD(1)),
      winner_color Enum8('unknown'=0, 'white'=1, 'black'=2, 'draw'=3) CODEC(ZSTD(1)),
      date         DateTime CODEC(Delta, ZSTD(1)),
      analysed     Bool CODEC(ZSTD(1)),
      white_user   String CODEC(ZSTD(1)),
      black_user   String CODEC(ZSTD(1)),
      white_rating UInt16 CODEC(ZSTD(1)),
      black_rating UInt16 CODEC(ZSTD(1)),
      ai_level     UInt8 CODEC(ZSTD(1)),
      duration     UInt16 CODEC(ZSTD(1)),
      clock_init   Nullable(UInt16) CODEC(ZSTD(1)),
      clock_inc    Nullable(UInt16) CODEC(ZSTD(1)),
      source       Nullable(UInt8) CODEC(ZSTD(1)),
      chess960_pos UInt16 CODEC(ZSTD(1)),

      INDEX idx_white white_user TYPE bloom_filter(0.01) GRANULARITY 1,
      INDEX idx_black black_user TYPE bloom_filter(0.01) GRANULARITY 1
    ) ENGINE = ReplacingMergeTree()
    PARTITION BY toYYYYMM(date)
    ORDER BY (date, id)
  """

  def create: ConnectionIO[Int] = Fragment.const(ddl).update.run

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
    aiLevel: Int,
    duration: Int,
    clockInit: Option[Int],
    clockInc: Option[Int],
    whiteUser: String,
    blackUser: String,
    source: Option[Int],
    chess960Position: Int // chess960 has 960 positions from 0 to 959, 1000 means it's not a chess960 game
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

  // doobie cannot auto-derive Write for 18-field case classes (tuple too wide),
  // so we split into two halves and compose via contramap.
  private type Half1 =
    (String, Int, Int, Boolean, Int, WinnerColor, Instant, Boolean, Int)
  private type Half2 =
    (Int, Int, Int, Option[Int], Option[Int], String, String, Option[Int], Int)

  given Write[GameRow] = Write[(Half1, Half2)].contramap: r =>
    (
      (r.id, r.status, r.turns, r.rated, r.perf, r.winnerColor, r.date, r.analysed, r.whiteRating),
      (r.blackRating, r.aiLevel, r.duration, r.clockInit, r.clockInc, r.whiteUser, r.blackUser, r.source, r.chess960Position)
    )
