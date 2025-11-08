package lila.search
package game.csv

import cats.syntax.all.*
import com.monovore.decline

import java.time.Instant

object CliTest extends weaver.FunSuite:

  def testCommand(args: String*) =
    decline.Command("lila-game-export", "Test Command")(opts.parse).parse(args)

  test("export command with all required options"):
    expect.same(
      testCommand(
        "--mongo-uri",
        "mongodb://localhost:27017",
        "--output",
        "games.csv",
        "--since",
        "0",
        "--until",
        "1"
      ),
      ExportOpts(
        mongoUri = "mongodb://localhost:27017",
        mongoDatabase = "lichess",
        batchSize = 1000,
        timeWindows = 5,
        format = "csv",
        output = "games.csv",
        since = Instant.ofEpochSecond(0),
        until = Instant.ofEpochSecond(1),
        watch = false
      ).asRight
    )

  test("export command with custom database"):
    val result = testCommand(
      "--mongo-uri",
      "mongodb://localhost:27017",
      "--mongo-database",
      "lichess-prod",
      "--output",
      "games.csv",
      "--since",
      "0"
    )
    result match
      case Right(opts: ExportOpts) =>
        expect(opts.mongoUri == "mongodb://localhost:27017") and
          expect(opts.mongoDatabase == "lichess-prod") and
          expect(opts.output == "games.csv") and
          expect(opts.since == Instant.ofEpochSecond(0))
      case _ => failure("Expected ExportOpts")

  test("export command with custom batch settings"):
    val result = testCommand(
      "--mongo-uri",
      "mongodb://localhost:27017",
      "--batch-size",
      "5000",
      "--time-windows",
      "10",
      "--output",
      "games.csv",
      "--since",
      "0"
    )
    result match
      case Right(opts: ExportOpts) =>
        expect(opts.batchSize == 5000) and
          expect(opts.timeWindows == 10) and
          expect(opts.mongoDatabase == "lichess") and
          expect(opts.output == "games.csv")
      case _ => failure("Expected ExportOpts")

  test("export command with watch flag"):
    val result = testCommand(
      "--mongo-uri",
      "mongodb://localhost:27017",
      "--output",
      "games.csv",
      "--since",
      "0",
      "--watch"
    )
    result match
      case Right(opts: ExportOpts) =>
        expect(opts.watch == true && opts.mongoUri == "mongodb://localhost:27017")
      case _ => failure("Expected ExportOpts")
