package lila.search
package clickhouse

import cats.effect.*
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import weaver.*

object ConnectionTest extends IOSuite:

  override type Res = Transactor[IO]

  override def sharedResource: Resource[IO, Res] =

    for
      config <- ClickHouseContainer.start
      xa <- CHTransactor.makeSimple(config)
    yield xa

  test("ClickHouse connection - ping"): xa =>
    sql"SELECT 1"
      .query[Int]
      .unique
      .transact(xa)
      .map: result =>
        expect(result == 1)

  test("ClickHouse connection - version check"): xa =>
    sql"SELECT version()"
      .query[String]
      .unique
      .transact(xa)
      .map: version =>
        expect(version.nonEmpty && version.contains("24."))

  test("ClickHouse connection - list databases"): xa =>
    sql"SELECT name FROM system.databases ORDER BY name"
      .query[String]
      .to[List]
      .transact(xa)
      .map: databases =>
        expect(databases.contains("default"))

  test("ClickHouse connection - create and query table"): xa =>
    val setup = sql"""
      CREATE TABLE IF NOT EXISTS default.test_table (
        id String,
        value Int32
      ) ENGINE = MergeTree()
      ORDER BY id
    """.update.run

    val insert = sql"""
      INSERT INTO default.test_table (id, value) VALUES ('test1', 42)
    """.update.run

    val query = sql"""
      SELECT id, value FROM default.test_table WHERE id = 'test1'
    """.query[(String, Int)].unique

    val cleanup = sql"DROP TABLE IF EXISTS default.test_table".update.run

    (setup *> insert *> query <* cleanup)
      .transact(xa)
      .map:
        case (id, value) =>
          expect(id == "test1" && value == 42)

object ConnectionPoolingTest extends IOSuite:

  override type Res = Transactor[IO]

  override def sharedResource: Resource[IO, Res] =
    val useLocal = sys.env.get("CLICKHOUSE_USE_LOCAL").exists(_ == "true")

    if useLocal then
      val config = CHTransactor.Config(
        host = "localhost",
        port = 8123,
        database = "default",
        user = "default",
        password = ""
      )
      CHTransactor.makePooled(config)
    else
      for
        config <- ClickHouseContainer.start
        xa <- CHTransactor.makePooled(config)
      yield xa

  test("ClickHouse HikariCP - concurrent queries"): xa =>
    // Run multiple queries concurrently to test connection pooling
    val queries = List.fill(10):
      sql"SELECT 1".query[Int].unique.transact(xa)

    queries.parSequence.map: results =>
      expect(results.forall(_ == 1))

  test("ClickHouse HikariCP - concurrent writes"): xa =>
    val tableName = s"test_concurrent_${System.currentTimeMillis()}"

    val setup = (fr"CREATE TABLE IF NOT EXISTS default." ++ Fragment.const(tableName) ++ fr"""(
        id String,
        value Int32
      ) ENGINE = MergeTree()
      ORDER BY id
    """).update.run

    val inserts = (1 to 20).toList.map: i =>
      (fr"INSERT INTO default." ++ Fragment.const(
        tableName
      ) ++ fr" (id, value) VALUES (${s"id_$i"}, $i)").update.run.transact(xa)

    val count = (fr"SELECT COUNT(*) FROM default." ++ Fragment.const(tableName)).query[Long].unique

    val cleanup = (fr"DROP TABLE IF EXISTS default." ++ Fragment.const(tableName)).update.run

    (setup.transact(xa) *> inserts.parSequence *> count.transact(xa) <* cleanup.transact(xa)).map: result =>
      expect(result == 20L)
