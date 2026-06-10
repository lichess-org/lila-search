package lila.search

import lila.search.game.Game
import weaver.*

object GameQueryTest extends FunSuite:

  private def queryString(game: Game): String =
    game.searchDef(From(0), Size(10)).query.get.toString

  test("Draw result also matches stalemates and insufficient material claims"):
    val query = queryString(Game(status = Some(Game.drawStatusId)))
    expect(query.contains("32") && query.contains("34") && query.contains("39"))

  test("a non-Draw result matches its status exactly"):
    val query = queryString(Game(status = Some(30))) // Mate
    expect(query.contains("30") && !query.contains("32") && !query.contains("39"))
