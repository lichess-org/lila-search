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
      winner_color Nullable(Int8) CODEC(ZSTD(1)),
      date         DateTime CODEC(Delta, ZSTD(1)),
      analysed     Bool CODEC(ZSTD(1)),
      white_user   String CODEC(ZSTD(1)),
      black_user   String CODEC(ZSTD(1)),
      avg_rating   Nullable(UInt16) CODEC(ZSTD(1)),
      ai_level     Nullable(UInt8) CODEC(ZSTD(1)),
      duration     Nullable(UInt16) CODEC(ZSTD(1)),
      clock_init   Nullable(UInt16) CODEC(ZSTD(1)),
      clock_inc    Nullable(UInt16) CODEC(ZSTD(1)),
      source       Nullable(UInt8) CODEC(ZSTD(1)),

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
    winnerColor: Option[Int],
    date: Instant,
    analysed: Boolean,
    avgRating: Option[Int],
    aiLevel: Option[Int],
    duration: Option[Int],
    clockInit: Option[Int],
    clockInc: Option[Int],
    whiteUser: String,
    blackUser: String,
    source: Option[Int]
)

object GameRow:
  // ClickHouse DateTime maps to java.sql.Timestamp via JDBC.
  given Meta[Instant] =
    Meta[java.sql.Timestamp].timap(_.toInstant)(java.sql.Timestamp.from)
