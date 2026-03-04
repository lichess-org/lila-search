package lila.search
package clickhouse

import lila.search.clickhouse.game.GameRow

import java.time.Instant

object Fixtures:

  def game(
      id: String = "testgame1",
      players: List[String] = List("alice", "bob"),
      turns: Int = 40,
      rated: Boolean = false,
      perf: Int = 1,
      aiLevel: Option[Int] = None,
      date: Long = Instant.now().toEpochMilli,
      status: Int = 30,
      avgRating: Option[Int] = None
  ): GameRow =
    GameRow(
      id = id,
      status = status,
      turns = turns,
      rated = rated,
      perf = perf,
      winnerColor = 3,
      date = date,
      analysed = false,
      uids = players,
      winner = None,
      loser = None,
      avgRating = avgRating,
      aiLevel = aiLevel,
      duration = None,
      clockInit = None,
      clockInc = None,
      whiteUser = players.headOption,
      blackUser = players.lift(1),
      source = None
    )
