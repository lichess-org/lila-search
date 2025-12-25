package lila.search
package clickhouse

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Example program demonstrating ClickHouse connectivity with Doobie
  *
  * Run this with: sbt "clickhouse/run"
  *
  * This will:
  * 1. Connect to ClickHouse
  * 2. Query the database version
  * 3. List available databases
  * 4. Query the game table
  * 5. Demonstrate batch operations
  */
object ConnectionExample extends IOApp.Simple:

  given Logger[IO] = Slf4jLogger.getLogger[IO]

  val config = CHTransactor.Config(
    host = "localhost",
    port = 8123,
    database = "lichess",
    user = "default",
    password = ""
  )

  def run: IO[Unit] =
    CHTransactor.makePooled(config).use: xa =>
      for
        _ <- IO.println("🚀 ClickHouse Connection Example")
        _ <- IO.println("=" * 50)
        _ <- checkVersion(xa)
        _ <- listDatabases(xa)
        _ <- listTables(xa)
        _ <- queryGames(xa)
        _ <- demonstrateBatch(xa)
        _ <- IO.println("=" * 50)
        _ <- IO.println("✅ Connection test complete!")
      yield ()

  def checkVersion(xa: Transactor[IO]): IO[Unit] =
    for
      _ <- IO.println("\n📋 ClickHouse Version:")
      version <- sql"SELECT version()".query[String].unique.transact(xa)
      _ <- IO.println(s"   Version: $version")
    yield ()

  def listDatabases(xa: Transactor[IO]): IO[Unit] =
    for
      _ <- IO.println("\n📁 Databases:")
      dbs <- sql"SELECT name FROM system.databases ORDER BY name"
        .query[String]
        .to[List]
        .transact(xa)
      _ <- dbs.traverse_(db => IO.println(s"   - $db"))
    yield ()

  def listTables(xa: Transactor[IO]): IO[Unit] =
    for
      _ <- IO.println("\n📊 Tables in 'lichess' database:")
      tables <- sql"SELECT name FROM system.tables WHERE database = 'lichess' ORDER BY name"
        .query[String]
        .to[List]
        .transact(xa)
      _ <- tables.traverse_(table => IO.println(s"   - $table"))
    yield ()

  def queryGames(xa: Transactor[IO]): IO[Unit] =
    val countAndDisplay = for
      _ <- IO.println("\n🎮 Game Table Stats:")
      count <- sql"SELECT COUNT(*) FROM lichess.game".query[Long].unique.transact(xa)
      _ <- IO.println(s"   Total games: $count")
    yield count

    countAndDisplay.flatMap: count =>
      if count > 0 then
        val fetchSample = sql"""
          SELECT id, winner, loser, turns, averageRating
          FROM lichess.game
          LIMIT 3
        """
          .query[(String, String, String, Int, Option[Int])]
          .to[List]
          .transact(xa)

        fetchSample.flatMap: sample =>
          IO.println("\n   Sample games:") *>
            sample.traverse_((id, winner, loser, turns, rating) =>
              IO.println(
                f"   - $id: $winner%-8s vs $loser%-8s ($turns%3d turns, rating: ${rating.getOrElse(0)}%4d)"
              )
            )
      else IO.println("   (No games in table)")

  def demonstrateBatch(xa: Transactor[IO]): IO[Unit] =
    for
      _ <- IO.println("\n🔧 Demonstrating Batch Operations:")
      _ <- IO.println("   Testing connection pooling with concurrent queries...")

      // Run 10 queries concurrently to demonstrate connection pooling
      queries = List.range(0, 10).map: i =>
        sql"SELECT $i"
          .query[Int]
          .unique
          .transact(xa)
          .timed
          .flatMap: (duration, result) =>
            IO.println(s"   Query $i: $result (${duration.toMillis}ms)")

      _ <- queries.parSequence
      _ <- IO.println("   ✅ All queries completed successfully")
    yield ()
