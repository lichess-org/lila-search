package lila.search
package clickhouse

import cats.effect.IO
import lila.search.Range
import lila.search.clickhouse.game.WinnerColor
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
          Fixtures.game(id = "wc1", players = List("user_wc1", "user_wc2"), winnerColor = WinnerColor.White),
          Fixtures.game(id = "wc2", players = List("user_wc1", "user_wc2"), winnerColor = WinnerColor.Draw)
        )
      )
      white <- ch.searchGames(
        Game(user1 = Some("user_wc1"), winnerColor = Some(1)),
        From(0),
        Size(10)
      )
      black <- ch.searchGames(
        Game(user1 = Some("user_wc1"), winnerColor = Some(2)),
        From(0),
        Size(10)
      )
      draw <- ch.searchGames(
        Game(user1 = Some("user_wc1"), winnerColor = Some(3)),
        From(0),
        Size(10)
      )
    yield expect(white.contains("wc1")) and expect(!white.contains("wc2")) and expect(
      black.isEmpty
    ) and expect(
      draw.size == 1
    ) and expect(draw.contains("wc2"))
  }

  test("winner filter derives from winner_color and users") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          // white wins: white=user_win1, black=user_win2
          Fixtures.game(id = "w1", players = List("user_win1", "user_win2"), winnerColor = WinnerColor.White),
          // black wins: white=user_win1, black=user_win2
          Fixtures.game(id = "w2", players = List("user_win1", "user_win2"), winnerColor = WinnerColor.Black),
          // draw
          Fixtures.game(id = "w3", players = List("user_win1", "user_win2"), winnerColor = WinnerColor.Draw)
        )
      )
      // user_win1 won game w1 (as white)
      wonByUser1 <- ch.searchGames(
        Game(winner = Some("user_win1")),
        From(0),
        Size(10)
      )
      // user_win2 won game w2 (as black)
      wonByUser2 <- ch.searchGames(
        Game(winner = Some("user_win2")),
        From(0),
        Size(10)
      )
      // user_win1 lost game w2 (as white, black won)
      lostByUser1 <- ch.searchGames(
        Game(loser = Some("user_win1")),
        From(0),
        Size(10)
      )
    yield expect(wonByUser1 == List("w1")) and
      expect(wonByUser2 == List("w2")) and
      expect(lostByUser1 == List("w2"))
  }

  test("hasAi filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "a1", players = List("user_ai1"), aiLevel = 5),
          Fixtures.game(id = "a2", players = List("user_ai1"))
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

  test("white_user filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "wu1", players = List("white_alice", "black_bob")),
          Fixtures.game(id = "wu2", players = List("white_carol", "black_bob"))
        )
      )
      ids <- ch.searchGames(
        Game(whiteUser = Some("white_alice")),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("wu1")) and expect(!ids.contains("wu2"))
  }

  test("black_user filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "bu1", players = List("white_dave", "black_eve")),
          Fixtures.game(id = "bu2", players = List("white_dave", "black_frank"))
        )
      )
      ids <- ch.searchGames(
        Game(blackUser = Some("black_eve")),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("bu1")) and expect(!ids.contains("bu2"))
  }

  test("avg_rating range filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "ar1", players = List("user_avgr1"), whiteRating = 1000, blackRating = 1000),
          Fixtures.game(id = "ar2", players = List("user_avgr1"), whiteRating = 1500, blackRating = 1500),
          Fixtures.game(id = "ar3", players = List("user_avgr1"), whiteRating = 2000, blackRating = 2000)
        )
      )
      // min only
      above1200 <- ch.searchGames(
        Game(user1 = Some("user_avgr1"), averageRating = Range(Some(1200), None)),
        From(0),
        Size(10)
      )
      // max only
      below1600 <- ch.searchGames(
        Game(user1 = Some("user_avgr1"), averageRating = Range(None, Some(1600))),
        From(0),
        Size(10)
      )
      // both min and max
      between <- ch.searchGames(
        Game(user1 = Some("user_avgr1"), averageRating = Range(Some(1200), Some(1800))),
        From(0),
        Size(10)
      )
    yield expect(above1200.toSet == Set("ar2", "ar3")) and
      expect(below1600.toSet == Set("ar1", "ar2")) and
      expect(between == List("ar2"))
  }

  test("ai_level range filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "al1", players = List("user_ail1"), aiLevel = 1),
          Fixtures.game(id = "al2", players = List("user_ail1"), aiLevel = 5),
          Fixtures.game(id = "al3", players = List("user_ail1"), aiLevel = 8)
        )
      )
      low <- ch.searchGames(
        Game(user1 = Some("user_ail1"), aiLevel = Range(None, Some(3))),
        From(0),
        Size(10)
      )
      high <- ch.searchGames(
        Game(user1 = Some("user_ail1"), aiLevel = Range(Some(4), Some(6))),
        From(0),
        Size(10)
      )
    yield expect(low == List("al1")) and expect(high == List("al2"))
  }

  test("duration range filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "du1", players = List("user_dur1"), duration = 60),
          Fixtures.game(id = "du2", players = List("user_dur1"), duration = 300),
          Fixtures.game(id = "du3", players = List("user_dur1"), duration = 600)
        )
      )
      short <- ch.searchGames(
        Game(user1 = Some("user_dur1"), duration = Range(None, Some(120))),
        From(0),
        Size(10)
      )
      mid <- ch.searchGames(
        Game(user1 = Some("user_dur1"), duration = Range(Some(200), Some(400))),
        From(0),
        Size(10)
      )
      long <- ch.searchGames(
        Game(user1 = Some("user_dur1"), duration = Range(Some(500), None)),
        From(0),
        Size(10)
      )
    yield expect(short == List("du1")) and
      expect(mid == List("du2")) and
      expect(long == List("du3"))
  }

  test("clock_init exact filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "ci1", players = List("user_ci1"), clockInit = Some(300)),
          Fixtures.game(id = "ci2", players = List("user_ci1"), clockInit = Some(600))
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_ci1"), clockInit = Some(300)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("ci1")) and expect(!ids.contains("ci2"))
  }

  test("clock_inc exact filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "cinc1", players = List("user_cinc1"), clockInc = Some(0)),
          Fixtures.game(id = "cinc2", players = List("user_cinc1"), clockInc = Some(5))
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_cinc1"), clockInc = Some(5)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("cinc2")) and expect(!ids.contains("cinc1"))
  }

  test("source filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "src1", players = List("user_src1"), source = Some(1)),
          Fixtures.game(id = "src2", players = List("user_src1"), source = Some(2)),
          Fixtures.game(id = "src3", players = List("user_src1"), source = Some(3))
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_src1"), source = Some(2)),
        From(0),
        Size(10)
      )
    yield expect(ids == List("src2"))
  }

  test("combined range filters narrow results") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(
            id = "combo1",
            players = List("user_combo1"),
            turns = 50,
            whiteRating = 1500,
            blackRating = 1500,
            duration = 300
          ),
          Fixtures.game(
            id = "combo2",
            players = List("user_combo1"),
            turns = 80,
            whiteRating = 1500,
            blackRating = 1500,
            duration = 300
          ),
          Fixtures.game(
            id = "combo3",
            players = List("user_combo1"),
            turns = 50,
            whiteRating = 2000,
            blackRating = 2000,
            duration = 300
          ),
          Fixtures.game(
            id = "combo4",
            players = List("user_combo1"),
            turns = 50,
            whiteRating = 1500,
            blackRating = 1500,
            duration = 60
          )
        )
      )
      ids <- ch.searchGames(
        Game(
          user1 = Some("user_combo1"),
          turns = Range(Some(30), Some(60)),
          averageRating = Range(Some(1400), Some(1600)),
          duration = Range(Some(200), Some(400))
        ),
        From(0),
        Size(10)
      )
    yield expect(ids == List("combo1"))
  }
