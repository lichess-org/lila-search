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

  test("opponentRating with a reference player constrains the opposite color's rating"):
    val query = queryString(
      Game(user1 = Some("alice"), opponentRating = Range(Some(1500), Some(2000)))
    )
    expect(
      query.contains("wr") && query.contains("br") &&
        query.contains("wu") && query.contains("bu") &&
        query.contains("alice") && query.contains("1500") && query.contains("2000")
    )

  test("opponentRating without a reference player matches either color's rating"):
    val query = queryString(Game(opponentRating = Range(Some(1500), Some(2000))))
    expect(
      query.contains("wr") && query.contains("br") &&
        !query.contains("wu") && !query.contains("bu") &&
        query.contains("1500") && query.contains("2000")
    )
