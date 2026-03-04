package lila.search
package clickhouse.game

import cats.data.NonEmptyList
import doobie.*
import doobie.enumerated.JdbcType

import java.time.Instant
import scala.jdk.CollectionConverters.*

object GameTable:
  val ddl: String = """
    CREATE TABLE IF NOT EXISTS games (
      id           String,
      status       Int32,
      turns        Int32,
      rated        Bool,
      perf         Int32,
      winner_color Int32,
      date         DateTime,
      analysed     Bool,
      uids         Array(String),
      winner       Nullable(String),
      loser        Nullable(String),
      avg_rating   Nullable(Int32),
      ai_level     Nullable(Int32),
      duration     Nullable(Int32),
      clock_init   Nullable(Int32),
      clock_inc    Nullable(Int32),
      white_user   Nullable(String),
      black_user   Nullable(String),
      source       Nullable(Int32)
    ) ENGINE = ReplacingMergeTree()
    ORDER BY id
  """

  def create: ConnectionIO[Int] = Fragment.const(ddl).update.run

case class GameRow(
    id: String,
    status: Int,
    turns: Int,
    rated: Boolean,
    perf: Int,
    winnerColor: Int,
    date: Instant,
    analysed: Boolean,
    uids: List[String],
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

  // ClickHouse JDBC returns Array(String) as java.util.List via getObject().
  // For INSERT, array literals must be built via Fragment.const — JDBC parameter
  // binding does not work for ClickHouse array columns.
  given Meta[List[String]] = Meta.Advanced.many(
    NonEmptyList.one(JdbcType.Array),
    NonEmptyList.one("Array"),
    (rs, idx) =>
      rs.getObject(idx) match
        case lst: java.util.List[?] => lst.asScala.toList.map(_.toString)
        case arr: Array[?] => arr.toList.map(_.toString)
        case _ => Nil,
    (ps, idx, v) => ps.setObject(idx, v.asJava),
    (rs, idx, v) => rs.updateObject(idx, v.asJava)
  )
