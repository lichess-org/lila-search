package lila.search
package clickhouse

import cats.effect.IO
import lila.search.game.{ Fields, Game, Sorting }
import weaver.IOSuite

import java.time.Instant

object GameSearchSuite extends IOSuite:
  override type Res = ClickHouseClient[IO]
  override def sharedResource = ClickHouseContainerSetup.resource

  test("range filter on turns") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "t1", players = List("user_turns1"), turns = 20),
          Fixtures.game(id = "t2", players = List("user_turns1"), turns = 100)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_turns1"), turns = Range(Some(50), None)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("t2")) and expect(!ids.contains("t1"))
  }

  test("perf IN filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "p1", players = List("user_perf1"), perf = 1),
          Fixtures.game(id = "p2", players = List("user_perf1"), perf = 2)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_perf1"), perf = List(1)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("p1")) and expect(!ids.contains("p2"))
  }

  test("rated filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "r1", players = List("user_rated1"), rated = true),
          Fixtures.game(id = "r2", players = List("user_rated1"), rated = false)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_rated1"), rated = Some(true)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("r1")) and expect(!ids.contains("r2"))
  }

  test("winner color filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "r1", players = List("user_rated1"), rated = true, winnerColor = Some(1)),
          Fixtures.game(id = "r2", players = List("user_rated1"), rated = false, winnerColor = Some(3))
        )
      )
      white <- ch.searchGames(
        Game(user1 = Some("user_rated1"), winnerColor = Some(1)),
        From(0),
        Size(10)
      )
      black <- ch.searchGames(
        Game(winnerColor = Some(2)),
        From(0),
        Size(10)
      )
      draw <- ch.searchGames(
        Game(winnerColor = Some(3)),
        From(0),
        Size(10)
      )
    yield expect(white.contains("r1")) and expect(!white.contains("r2")) and expect(black.isEmpty) and expect(draw.size == 1) and expect(draw.contains("r2"))
  }

  test("hasAi filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "a1", players = List("user_ai1"), aiLevel = Some(5)),
          Fixtures.game(id = "a2", players = List("user_ai1"), aiLevel = None)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_ai1"), hasAi = Some(true)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("a1")) and expect(!ids.contains("a2"))
  }

  test("sort by date descending") { ch =>
    val earlyDate = Instant.ofEpochSecond(1_000_000_000L)
    val lateDate = Instant.ofEpochSecond(2_000_000_000L)
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "d1", players = List("user_sort1"), date = earlyDate),
          Fixtures.game(id = "d2", players = List("user_sort1"), date = lateDate)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_sort1"), sorting = Sorting(Fields.date, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids.indexOf("d2") < ids.indexOf("d1"))
  }

  test("pagination with from/size") { ch =>
    val games = (1 to 5).toList.map(i => Fixtures.game(id = s"pg$i", players = List("user_pagination1")))
    for
      _ <- ch.upsertGameRows(games)
      page <- ch.searchGames(Game(user1 = Some("user_pagination1")), From(2), Size(2))
    yield expect(page.size == 2)
  }

  test("count matches search result size") { ch =>
    val games =
      (1 to 4).toList.map(i => Fixtures.game(id = s"cnt$i", players = List("user_count1"), rated = true))
    for
      _ <- ch.upsertGameRows(games)
      n <- ch.countGames(Game(user1 = Some("user_count1"), rated = Some(true)))
      ids <- ch.searchGames(Game(user1 = Some("user_count1"), rated = Some(true)), From(0), Size(100))
    yield expect(n == ids.size.toLong)
  }
