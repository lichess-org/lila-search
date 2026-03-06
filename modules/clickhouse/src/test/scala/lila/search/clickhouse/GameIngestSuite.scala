package lila.search
package clickhouse

import cats.effect.IO
import lila.search.game.Game
import weaver.IOSuite

object GameIngestSuite extends IOSuite:
  override type Res = ClickHouseClient[IO]
  override def sharedResource = ClickHouseContainerSetup.resource

  test("upserted game is searchable by uid") { ch =>
    val g = Fixtures.game(id = "g1", players = List("ingest_alice"))
    for
      _ <- ch.upsertGameRows(List(g))
      ids <- ch.searchGames(Game(user1 = Some("ingest_alice")), From(0), Size(10))
    yield expect(ids.contains("g1"))
  }

  test("upsert is idempotent") { ch =>
    val g = Fixtures.game(id = "g2", players = List("ingest_charlie"))
    for
      _ <- ch.upsertGameRows(List(g))
      _ <- ch.upsertGameRows(List(g))
      n <- ch.countGames(Game(user1 = Some("ingest_charlie")))
    yield expect(n == 1L)
  }

  test("deleted game is not searchable") { ch =>
    val g = Fixtures.game(id = "g3", players = List("ingest_dave"))
    for
      _ <- ch.upsertGameRows(List(g))
      _ <- ch.deleteGames(List("g3"))
      ids <- ch.searchGames(Game(user1 = Some("ingest_dave")), From(0), Size(10))
    yield expect(!ids.contains("g3"))
  }

  test("batch upsert") { ch =>
    val games =
      List("h1", "h2", "h3").map(id => Fixtures.game(id = id, players = List("ingest_eve")))
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(Game(user1 = Some("ingest_eve")), From(0), Size(10))
    yield expect(ids.toSet == Set("h1", "h2", "h3"))
  }

  test("upsert game with chess960 position") { ch =>
    val g = Fixtures.game(id = "c960_1", players = List("ingest_960"), chess960Position = 518)
    for
      _ <- ch.upsertGameRows(List(g))
      ids <- ch.searchGames(Game(user1 = Some("ingest_960")), From(0), Size(10))
    yield expect(ids.contains("c960_1"))
  }

  test("upsert game with default chess960 position (non-960 game)") { ch =>
    val g = Fixtures.game(id = "c960_2", players = List("ingest_non960"))
    for
      _ <- ch.upsertGameRows(List(g))
      ids <- ch.searchGames(Game(user1 = Some("ingest_non960")), From(0), Size(10))
    yield expect(ids.contains("c960_2"))
  }

  test("batch upsert with mixed chess960 positions") { ch =>
    val games = List(
      Fixtures.game(id = "c960_3", players = List("ingest_mix960"), chess960Position = 0),
      Fixtures.game(id = "c960_4", players = List("ingest_mix960"), chess960Position = 959),
      Fixtures.game(id = "c960_5", players = List("ingest_mix960"), chess960Position = 1000)
    )
    for
      _ <- ch.upsertGameRows(games)
      ids <- ch.searchGames(Game(user1 = Some("ingest_mix960")), From(0), Size(10))
    yield expect(ids.toSet == Set("c960_3", "c960_4", "c960_5"))
  }
