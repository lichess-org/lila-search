package lila.search
package clickhouse.game

import doobie.*

import java.time.Instant

object GameTable:
  val ddl: String = """
    CREATE TABLE IF NOT EXISTS games (
      id           String CODEC(LZ4),
      status       Int32 CODEC(ZSTD(1)),
      turns        Int32 CODEC(ZSTD(1)),
      rated        Bool CODEC(ZSTD(1)),
      perf         Int32 CODEC(ZSTD(1)),
      winner_color Nullable(Int8) CODEC(ZSTD(1)),
      date         DateTime CODEC(Delta, ZSTD(1)),
      analysed     Bool CODEC(ZSTD(1)),
      white_user   Nullable(String) CODEC(ZSTD(1)),
      black_user   Nullable(String) CODEC(ZSTD(1)),
      winner       Nullable(String) CODEC(ZSTD(1)),
      loser        Nullable(String) CODEC(ZSTD(1)),
      avg_rating   Nullable(Int32) CODEC(ZSTD(1)),
      ai_level     Nullable(Int32) CODEC(ZSTD(1)),
      duration     Nullable(Int32) CODEC(ZSTD(1)),
      clock_init   Nullable(Int32) CODEC(ZSTD(1)),
      clock_inc    Nullable(Int32) CODEC(ZSTD(1)),
      source       Nullable(Int32) CODEC(ZSTD(1)),

      INDEX idx_white white_user TYPE bloom_filter(0.01) GRANULARITY 1,
      INDEX idx_black black_user TYPE bloom_filter(0.01) GRANULARITY 1,
      INDEX idx_winner winner TYPE bloom_filter(0.01) GRANULARITY 1,
      INDEX idx_loser loser TYPE bloom_filter(0.01) GRANULARITY 1
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
    winner: Option[String],
    loser: Option[String],
    avgRating: Option[Int],
    aiLevel: Option[Int],
    duration: Option[Int],
    clockInit: Option[Int],
    clockInc: Option[Int],
    whiteUser: Option[String],
    blackUser: Option[String],
    source: Option[Int]
)

object GameRow:
  // ClickHouse DateTime maps to java.sql.Timestamp via JDBC.
  given Meta[Instant] =
    Meta[java.sql.Timestamp].timap(_.toInstant)(java.sql.Timestamp.from)
