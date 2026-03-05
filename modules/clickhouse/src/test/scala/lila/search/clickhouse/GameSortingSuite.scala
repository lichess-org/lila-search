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
      Fixtures.game(id = "sd1", players = List(s"${u}date1"), date = Instant.ofEpochSecond(1_000L)),
      Fixtures.game(id = "sd2", players = List(s"${u}date1"), date = Instant.ofEpochSecond(2_000L)),
      Fixtures.game(id = "sd3", players = List(s"${u}date1"), date = Instant.ofEpochSecond(3_000L))
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}date1"), sorting = Sorting(Fields.date, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("sd3", "sd2", "sd1"))
  }

  test("sort by date asc returns oldest first") { ch =>
    val games = List(
      Fixtures.game(id = "sa1", players = List(s"${u}date2"), date = Instant.ofEpochSecond(1_000L)),
      Fixtures.game(id = "sa2", players = List(s"${u}date2"), date = Instant.ofEpochSecond(2_000L)),
      Fixtures.game(id = "sa3", players = List(s"${u}date2"), date = Instant.ofEpochSecond(3_000L))
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}date2"), sorting = Sorting(Fields.date, "asc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("sa1", "sa2", "sa3"))
  }

  test("sort by turns desc returns most turns first") { ch =>
    val games = List(
      Fixtures.game(id = "st1", players = List(s"${u}turns1"), turns = 10),
      Fixtures.game(id = "st2", players = List(s"${u}turns1"), turns = 50),
      Fixtures.game(id = "st3", players = List(s"${u}turns1"), turns = 30)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}turns1"), sorting = Sorting(Fields.turns, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("st2", "st3", "st1"))
  }

  test("sort by turns asc returns fewest turns first") { ch =>
    val games = List(
      Fixtures.game(id = "su1", players = List(s"${u}turns2"), turns = 10),
      Fixtures.game(id = "su2", players = List(s"${u}turns2"), turns = 50),
      Fixtures.game(id = "su3", players = List(s"${u}turns2"), turns = 30)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}turns2"), sorting = Sorting(Fields.turns, "asc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("su1", "su3", "su2"))
  }

  test("sort by averageRating desc returns highest rated first") { ch =>
    val games = List(
      Fixtures.game(id = "sr1", players = List(s"${u}rating1"), whiteRating = 1200, blackRating = 1200),
      Fixtures.game(id = "sr2", players = List(s"${u}rating1"), whiteRating = 1800, blackRating = 1800),
      Fixtures.game(id = "sr3", players = List(s"${u}rating1"), whiteRating = 1500, blackRating = 1500)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}rating1"), sorting = Sorting(Fields.averageRating, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("sr2", "sr3", "sr1"))
  }

  test("sort by averageRating asc returns lowest rated first") { ch =>
    val games = List(
      Fixtures.game(id = "sq1", players = List(s"${u}rating2"), whiteRating = 1200, blackRating = 1200),
      Fixtures.game(id = "sq2", players = List(s"${u}rating2"), whiteRating = 1800, blackRating = 1800),
      Fixtures.game(id = "sq3", players = List(s"${u}rating2"), whiteRating = 1500, blackRating = 1500)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}rating2"), sorting = Sorting(Fields.averageRating, "asc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("sq1", "sq3", "sq2"))
  }

  test("unknown sort field falls back to date desc") { ch =>
    val games = List(
      Fixtures.game(id = "sf1", players = List(s"${u}fallback1"), date = Instant.ofEpochSecond(1_000L)),
      Fixtures.game(id = "sf2", players = List(s"${u}fallback1"), date = Instant.ofEpochSecond(2_000L))
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(
        Game(user1 = Some(s"${u}fallback1"), sorting = Sorting("unknown_field", "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids == List("sf2", "sf1"))
  }
