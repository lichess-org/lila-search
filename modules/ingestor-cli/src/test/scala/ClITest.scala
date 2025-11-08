package lila.search
package ingestor

import cats.syntax.all.*
import com.monovore.decline
import lila.search.game.csv.ExportOpts

import java.time.Instant

import opts.*

object CLITest extends weaver.FunSuite:

  def testCommand(args: String*) =
    decline.Command("test", "Test Command")(opts.parse).parse(args)

  test("index command"):
    expect.same(
      testCommand("index", "--index", "team", "--since", "0", "--until", "1", "--dry"),
      IndexOpts(Index.Team, Instant.ofEpochSecond(0), Instant.ofEpochSecond(1), false, true, false).asRight
    )

  test("index command with watch flag"):
    val result = testCommand("index", "--index", "team", "--since", "0", "--watch", "--dry")
    result match
      case Right(opts: IndexOpts) =>
        expect(opts.watch == true && opts.dry == true && opts.index == Index.Team)
      case _ => failure("Expected IndexOpts")

  test("export command"):
    expect.same(
      testCommand(
        "export",
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
