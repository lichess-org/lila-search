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
