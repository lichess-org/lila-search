package lila.search
package clickhouse

import cats.effect.IO
import lila.search.game.{ Fields, Game, Sorting }
import weaver.IOSuite

import java.time.Instant

object GameSortingSuite extends IOSuite:
  override type Res = ClickHouseClient[IO]
  override def sharedResource = ClickHouseContainerSetup.resource

  // Distinct user prefix per test so rows don't leak across tests
  private val u = "sort_"

  test("sort by date desc returns newest first") { ch =>
    val games = List(
      Fixtures.game(id = "srtdat_1", players = List(s"${u}date1"), date = Instant.ofEpochSecond(1_000L)),
      Fixtures.game(id = "srtdat_2", players = List(s"${u}date1"), date = Instant.ofEpochSecond(2_000L)),
      Fixtures.game(id = "srtdat_3", players = List(s"${u}date1"), date = Instant.ofEpochSecond(3_000L))
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}date1"), sorting = Sorting(Fields.date, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("srtdat_3", "srtdat_2", "srtdat_1"))
  }

  test("sort by date asc returns oldest first") { ch =>
    val games = List(
      Fixtures.game(id = "srtasc_1", players = List(s"${u}date2"), date = Instant.ofEpochSecond(1_000L)),
      Fixtures.game(id = "srtasc_2", players = List(s"${u}date2"), date = Instant.ofEpochSecond(2_000L)),
      Fixtures.game(id = "srtasc_3", players = List(s"${u}date2"), date = Instant.ofEpochSecond(3_000L))
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}date2"), sorting = Sorting(Fields.date, "asc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("srtasc_1", "srtasc_2", "srtasc_3"))
  }

  test("sort by turns desc returns most turns first") { ch =>
    val games = List(
      Fixtures.game(id = "srttrn_1", players = List(s"${u}turns1"), turns = 10),
      Fixtures.game(id = "srttrn_2", players = List(s"${u}turns1"), turns = 50),
      Fixtures.game(id = "srttrn_3", players = List(s"${u}turns1"), turns = 30)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}turns1"), sorting = Sorting(Fields.turns, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("srttrn_2", "srttrn_3", "srttrn_1"))
  }

  test("sort by turns asc returns fewest turns first") { ch =>
    val games = List(
      Fixtures.game(id = "srttna_1", players = List(s"${u}turns2"), turns = 10),
      Fixtures.game(id = "srttna_2", players = List(s"${u}turns2"), turns = 50),
      Fixtures.game(id = "srttna_3", players = List(s"${u}turns2"), turns = 30)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}turns2"), sorting = Sorting(Fields.turns, "asc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("srttna_1", "srttna_3", "srttna_2"))
  }

  test("sort by averageRating desc returns highest rated first") { ch =>
    val games = List(
      Fixtures.game(id = "srtrtd_1", players = List(s"${u}rating1"), whiteRating = 1200, blackRating = 1200),
      Fixtures.game(id = "srtrtd_2", players = List(s"${u}rating1"), whiteRating = 1800, blackRating = 1800),
      Fixtures.game(id = "srtrtd_3", players = List(s"${u}rating1"), whiteRating = 1500, blackRating = 1500)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}rating1"), sorting = Sorting(Fields.averageRating, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("srtrtd_2", "srtrtd_3", "srtrtd_1"))
  }

  test("sort by averageRating asc returns lowest rated first") { ch =>
    val games = List(
      Fixtures.game(id = "srtrta_1", players = List(s"${u}rating2"), whiteRating = 1200, blackRating = 1200),
      Fixtures.game(id = "srtrta_2", players = List(s"${u}rating2"), whiteRating = 1800, blackRating = 1800),
      Fixtures.game(id = "srtrta_3", players = List(s"${u}rating2"), whiteRating = 1500, blackRating = 1500)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}rating2"), sorting = Sorting(Fields.averageRating, "asc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("srtrta_1", "srtrta_3", "srtrta_2"))
  }

  test("unknown sort field falls back to date desc") { ch =>
    val games = List(
      Fixtures.game(id = "srtfbk_1", players = List(s"${u}fallback1"), date = Instant.ofEpochSecond(1_000L)),
      Fixtures.game(id = "srtfbk_2", players = List(s"${u}fallback1"), date = Instant.ofEpochSecond(2_000L))
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}fallback1"), sorting = Sorting("unknown_field", "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("srtfbk_2", "srtfbk_1"))
  }
