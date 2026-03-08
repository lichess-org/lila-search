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
          Fixtures.game(id = "turns_t1", players = List("user_turns1"), turns = 20),
          Fixtures.game(id = "turns_t2", players = List("user_turns1"), turns = 100)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_turns1"), turns = Range(Some(50), None)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("turns_t2")) and expect(!ids.contains("turns_t1"))
  }

  test("perf IN filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "perf__p1", players = List("user_perf1"), perf = 1),
          Fixtures.game(id = "perf__p2", players = List("user_perf1"), perf = 2)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_perf1"), perf = List(1)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("perf__p1")) and expect(!ids.contains("perf__p2"))
  }

  test("rated filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "rated_r1", players = List("user_rated1"), rated = true),
          Fixtures.game(id = "rated_r2", players = List("user_rated1"), rated = false)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_rated1"), rated = Some(true)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("rated_r1")) and expect(!ids.contains("rated_r2"))
  }

  test("winner color filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures
            .game(id = "wcolor1_", players = List("user_wc1", "user_wc2"), winnerColor = WinnerColor.White),
          Fixtures.game(
            id = "wcolor2_",
            players = List("user_wc1", "user_wc2"),
            winnerColor = WinnerColor.Draw
          )
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
    yield expect(white.contains("wcolor1_")) and expect(!white.contains("wcolor2_")) and expect(
      black.isEmpty
    ) and expect(
      draw.size == 1
    ) and expect(draw.contains("wcolor2_"))
  }

  test("winner filter derives from winner_color and users") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          // white wins: white=user_win1, black=user_win2
          Fixtures.game(
            id = "winner1_",
            players = List("user_win1", "user_win2"),
            winnerColor = WinnerColor.White
          ),
          // black wins: white=user_win1, black=user_win2
          Fixtures.game(
            id = "winner2_",
            players = List("user_win1", "user_win2"),
            winnerColor = WinnerColor.Black
          ),
          // draw
          Fixtures.game(
            id = "winner3_",
            players = List("user_win1", "user_win2"),
            winnerColor = WinnerColor.Draw
          )
        )
      )
      // user_win1 won game winner1_ (as white)
      wonByUser1 <- ch.searchGames(
        Game(winner = Some("user_win1")),
        From(0),
        Size(10)
      )
      // user_win2 won game winner2_ (as black)
      wonByUser2 <- ch.searchGames(
        Game(winner = Some("user_win2")),
        From(0),
        Size(10)
      )
      // user_win1 lost game winner2_ (as white, black won)
      lostByUser1 <- ch.searchGames(
        Game(loser = Some("user_win1")),
        From(0),
        Size(10)
      )
    yield expect(wonByUser1 == List("winner1_")) and
      expect(wonByUser2 == List("winner2_")) and
      expect(lostByUser1 == List("winner2_"))
  }

  test("hasAi filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "hasai_a1", players = List("user_ai1"), aiLevel = 5),
          Fixtures.game(id = "hasai_a2", players = List("user_ai1"))
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_ai1"), hasAi = Some(true)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("hasai_a1")) and expect(!ids.contains("hasai_a2"))
  }

  test("sort by date descending") { ch =>
    val earlyDate = Instant.ofEpochSecond(1_000_000_000L)
    val lateDate = Instant.ofEpochSecond(2_000_000_000L)
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "dsort_d1", players = List("user_sort1"), date = earlyDate),
          Fixtures.game(id = "dsort_d2", players = List("user_sort1"), date = lateDate)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_sort1"), sorting = Sorting(Fields.date, "desc")),
        From(0),
        Size(10)
      )
    yield expect(ids.indexOf("dsort_d2") < ids.indexOf("dsort_d1"))
  }

  test("pagination with from/size") { ch =>
    val games = (1 to 5).toList.map(i => Fixtures.game(id = f"pagin_p$i", players = List("user_pagination1")))
    for
      _ <- ch.upsertGameRows(games)
      page <- ch.searchGames(Game(user1 = Some("user_pagination1")), From(2), Size(2))
    yield expect(page.size == 2)
  }

  test("count matches search result size") { ch =>
    val games =
      (1 to 4).toList.map(i => Fixtures.game(id = f"count_c$i", players = List("user_count1"), rated = true))
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
          Fixtures.game(id = "wuser_u1", players = List("white_alice", "black_bob")),
          Fixtures.game(id = "wuser_u2", players = List("white_carol", "black_bob"))
        )
      )
      ids <- ch.searchGames(
        Game(whiteUser = Some("white_alice")),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("wuser_u1")) and expect(!ids.contains("wuser_u2"))
  }

  test("black_user filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "buser_u1", players = List("white_dave", "black_eve")),
          Fixtures.game(id = "buser_u2", players = List("white_dave", "black_frank"))
        )
      )
      ids <- ch.searchGames(
        Game(blackUser = Some("black_eve")),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("buser_u1")) and expect(!ids.contains("buser_u2"))
  }

  test("avg_rating range filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures
            .game(id = "avgrt_r1", players = List("user_avgr1"), whiteRating = 1000, blackRating = 1000),
          Fixtures
            .game(id = "avgrt_r2", players = List("user_avgr1"), whiteRating = 1500, blackRating = 1500),
          Fixtures.game(id = "avgrt_r3", players = List("user_avgr1"), whiteRating = 2000, blackRating = 2000)
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
    yield expect(above1200.toSet == Set("avgrt_r2", "avgrt_r3")) and
      expect(below1600.toSet == Set("avgrt_r1", "avgrt_r2")) and
      expect(between == List("avgrt_r2"))
  }

  test("avg_rating range filter excludes games with missing ratings") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures
            .game(id = "avgrt_r4", players = List("user_avgr2"), whiteRating = 1500, blackRating = 1500),
          Fixtures.game(id = "avgrt_r5", players = List("user_avgr2"), whiteRating = 1500, blackRating = 0),
          Fixtures.game(id = "avgrt_r6", players = List("user_avgr2"), whiteRating = 0, blackRating = 1500),
          Fixtures.game(id = "avgrt_r7", players = List("user_avgr2"), whiteRating = 0, blackRating = 0)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_avgr2"), averageRating = Range(Some(0), None)),
        From(0),
        Size(10)
      )
    yield expect(ids == List("avgrt_r4"))
  }

  test("ai_level range filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "ailev_l1", players = List("user_ail1"), aiLevel = 1),
          Fixtures.game(id = "ailev_l2", players = List("user_ail1"), aiLevel = 5),
          Fixtures.game(id = "ailev_l3", players = List("user_ail1"), aiLevel = 8)
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
    yield expect(low == List("ailev_l1")) and expect(high == List("ailev_l2"))
  }

  test("duration range filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "durat_d1", players = List("user_dur1"), duration = 60),
          Fixtures.game(id = "durat_d2", players = List("user_dur1"), duration = 300),
          Fixtures.game(id = "durat_d3", players = List("user_dur1"), duration = 600)
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
    yield expect(short == List("durat_d1")) and
      expect(mid == List("durat_d2")) and
      expect(long == List("durat_d3"))
  }

  test("clock_init exact filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "clinit_1", players = List("user_ci1"), clockInit = 300),
          Fixtures.game(id = "clinit_2", players = List("user_ci1"), clockInit = 600)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_ci1"), clockInit = Some(300)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("clinit_1")) and expect(!ids.contains("clinit_2"))
  }

  test("clock_inc exact filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "clinc__1", players = List("user_cinc1"), clockInc = 0),
          Fixtures.game(id = "clinc__2", players = List("user_cinc1"), clockInc = 5)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_cinc1"), clockInc = Some(5)),
        From(0),
        Size(10)
      )
    yield expect(ids.contains("clinc__2")) and expect(!ids.contains("clinc__1"))
  }

  test("source filter") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(id = "source_1", players = List("user_src1"), source = 1),
          Fixtures.game(id = "source_2", players = List("user_src1"), source = 2),
          Fixtures.game(id = "source_3", players = List("user_src1"), source = 3)
        )
      )
      ids <- ch.searchGames(
        Game(user1 = Some("user_src1"), source = Some(2)),
        From(0),
        Size(10)
      )
    yield expect(ids == List("source_2"))
  }

  test("combined range filters narrow results") { ch =>
    for
      _ <- ch.upsertGameRows(
        List(
          Fixtures.game(
            id = "combo__1",
            players = List("user_combo1"),
            turns = 50,
            whiteRating = 1500,
            blackRating = 1500,
            duration = 300
          ),
          Fixtures.game(
            id = "combo__2",
            players = List("user_combo1"),
            turns = 80,
            whiteRating = 1500,
            blackRating = 1500,
            duration = 300
          ),
          Fixtures.game(
            id = "combo__3",
            players = List("user_combo1"),
            turns = 50,
            whiteRating = 2000,
            blackRating = 2000,
            duration = 300
          ),
          Fixtures.game(
            id = "combo__4",
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
    yield expect(ids == List("combo__1"))
  }
