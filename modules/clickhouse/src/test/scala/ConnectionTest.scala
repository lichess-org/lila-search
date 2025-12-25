package lila.search
package clickhouse

import cats.effect.*
import doobie.*
import doobie.implicits.*
import weaver.*

object ConnectionTest extends SimpleIOSuite:

  // Configuration for local Docker ClickHouse instance
  val config = CHTransactor.Config(
    host = "localhost",
    port = 8123,
    database = "lichess",
    user = "default",
    password = ""
  )

  test("ClickHouse connection - ping"):
    CHTransactor.makeSimple(config).use: xa =>
      sql"SELECT 1".query[Int].unique.transact(xa).map: result =>
        expect(result == 1)

  test("ClickHouse connection - version check"):
    CHTransactor.makeSimple(config).use: xa =>
      sql"SELECT version()".query[String].unique.transact(xa).map: version =>
        expect(version.nonEmpty && version.contains("24."))

  test("ClickHouse connection - database exists"):
    CHTransactor.makeSimple(config).use: xa =>
      sql"SELECT name FROM system.databases WHERE name = 'lichess'"
        .query[String]
        .unique
        .transact(xa)
        .map: db =>
          expect(db == "lichess")

  test("ClickHouse connection - game table exists"):
    CHTransactor.makeSimple(config).use: xa =>
      sql"SELECT name FROM system.tables WHERE database = 'lichess' AND name = 'game'"
        .query[String]
        .unique
        .transact(xa)
        .map: table =>
          expect(table == "game")

  test("ClickHouse connection - query game table"):
    CHTransactor.makeSimple(config).use: xa =>
      sql"SELECT COUNT(*) FROM lichess.game"
        .query[Long]
        .unique
        .transact(xa)
        .map: count =>
          expect(count >= 0) // At least 0 rows (could be empty or have test data)

  test("ClickHouse connection - query game sample"):
    CHTransactor.makeSimple(config).use: xa =>
      sql"SELECT id, winner, turns FROM lichess.game LIMIT 1"
        .query[(String, String, Int)]
        .option
        .transact(xa)
        .map:
          case Some((id, winner, turns)) =>
            expect(id.nonEmpty && winner.nonEmpty && turns > 0)
          case None =>
            // Table might be empty - that's OK for this test
            success

  test("ClickHouse connection - HikariCP pooled connection"):
    CHTransactor.makePooled(config).use: xa =>
      // Run multiple queries to test connection pooling
      val queries = List.fill(5):
        sql"SELECT 1".query[Int].unique.transact(xa)

      queries.sequence.map: results =>
        expect(results.forall(_ == 1))
