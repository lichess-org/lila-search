package lila.search
package clickhouse.game

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

object GameIngest:
  import GameRow.given

  // Column order must match GameRow case class field order for Write[GameRow] derivation
  private val insertSql =
    """INSERT INTO games
       (id,status,turns,rated,perf,winner_color,date,analysed,
        white_rating,black_rating,ai_level,duration,clock_init,clock_inc,
        white_user,black_user,source,chess960_pos,white_bot,black_bot)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""

  private val insertUpdate = Update[GameRow](insertSql)

  def upsertGames(rows: List[GameRow]): ConnectionIO[Unit] =
    insertUpdate.updateMany(rows).void

  // ClickHouse deletes use mutations which are asynchronous by default.
  // mutations_sync=1 waits for the mutation to complete on the local replica,
  // ensuring the delete is visible immediately after this call.
  def deleteGame(id: String): ConnectionIO[Int] =
    sql"ALTER TABLE games DELETE WHERE id = $id SETTINGS mutations_sync = 1".update.run

  // todo batch delete with multiple ids, but for now we only have single deletes in the tests
  def deleteGames(ids: List[String]): ConnectionIO[Unit] =
    ids.traverse_(deleteGame)

  def optimizePartition(partition: String): ConnectionIO[Unit] =
    Fragment.const(s"OPTIMIZE TABLE games PARTITION '$partition' FINAL").update.run.void

  def optimizeAll: ConnectionIO[Unit] =
    Fragment.const("OPTIMIZE TABLE games FINAL").update.run.void
