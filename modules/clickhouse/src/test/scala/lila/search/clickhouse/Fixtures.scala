package lila.search
package clickhouse

import lila.search.clickhouse.game.GameRow

import java.time.Instant
import java.time.temporal.ChronoUnit

object Fixtures:

  def game(
      id: String = "testgame1",
      players: List[String] = List("alice", "bob"),
      turns: Int = 40,
      rated: Boolean = false,
      perf: Int = 1,
      aiLevel: Option[Int] = None,
      date: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
      status: Int = 30,
      avgRating: Option[Int] = None,
      winnerColor: Option[Int] = None
  ): GameRow =
    GameRow(
      id = id,
      status = status,
      turns = turns,
      rated = rated,
      perf = perf,
      winnerColor = winnerColor,
      date = date,
      analysed = false,
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
