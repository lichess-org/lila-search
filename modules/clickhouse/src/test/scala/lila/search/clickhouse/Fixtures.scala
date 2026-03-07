package lila.search
package clickhouse

import lila.search.clickhouse.game.{ GameRow, WinnerColor }

import java.time.Instant
import java.time.temporal.ChronoUnit

object Fixtures:

  def game(
      id: String = "testgame1",
      players: List[String] = List("alice", "bob"),
      turns: Int = 40,
      rated: Boolean = false,
      perf: Int = 1,
      aiLevel: Int = 0,
      date: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
      status: Int = 30,
      whiteRating: Int = 0,
      blackRating: Int = 0,
      winnerColor: WinnerColor = WinnerColor.Unknown,
      duration: Int = 0,
      clockInit: Int = -1,
      clockInc: Int = -1,
      source: Int = 0,
      chess960Position: Int = 1000,
      whiteBot: Boolean = false,
      blackBot: Boolean = false
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
      whiteRating = whiteRating,
      blackRating = blackRating,
      aiLevel = aiLevel,
      duration = duration,
      clockInit = clockInit,
      clockInc = clockInc,
      whiteUser = players.headOption.getOrElse(""),
      blackUser = players.lift(1).getOrElse(""),
      source = source,
      chess960Position = chess960Position,
      whiteBot = whiteBot,
      blackBot = blackBot
    )
