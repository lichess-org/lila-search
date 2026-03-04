package lila.search
package clickhouse.game

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

object GameIngest:
  import GameRow.given

  def upsertGame(r: GameRow): ConnectionIO[Int] =
    val uidsLit = Fragment.const(r.uids.map(u => s"'${u.replace("'", "\\'")}'").mkString("[", ",", "]"))
    (fr"""INSERT INTO games
         (id,status,turns,rated,perf,winner_color,date,analysed,
          uids,winner,loser,avg_rating,ai_level,duration,clock_init,clock_inc,
          white_user,black_user,source)
         VALUES (
         ${r.id},${r.status},${r.turns},${r.rated},${r.perf},${r.winnerColor},
         ${r.date},${r.analysed},""" ++
      uidsLit ++
      fr""",${r.winner},${r.loser},${r.avgRating},${r.aiLevel},
         ${r.duration},${r.clockInit},${r.clockInc},
         ${r.whiteUser},${r.blackUser},${r.source})""").update.run

  def upsertGames(rows: List[GameRow]): ConnectionIO[Unit] =
    rows.traverse_(upsertGame)

  // ClickHouse deletes use mutations which are asynchronous by default.
  // mutations_sync=1 waits for the mutation to complete on the local replica,
  // ensuring the delete is visible immediately after this call.
  def deleteGame(id: String): ConnectionIO[Int] =
    sql"ALTER TABLE games DELETE WHERE id = $id SETTINGS mutations_sync = 1".update.run

  def deleteGames(ids: List[String]): ConnectionIO[Unit] =
    ids.traverse_(deleteGame)
