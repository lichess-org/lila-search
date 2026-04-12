package lila.search
package ingestor

import cats.syntax.all.*
import com.monovore.decline

import java.time.Instant

import opts.*

object CLITest extends weaver.FunSuite:

  def testIndexCommand(args: String*) =
    decline.Command("test", "Test Command")(opts.parseIndex).parse(args)

  test("index command"):
    expect.same(
      testIndexCommand("index", "--index", "team", "--since", "0", "--until", "1", "--dry"),
      IndexOpts(Index.Team, Instant.ofEpochSecond(0), Instant.ofEpochSecond(1), false, true, false).asRight
    )

  test("index command with watch flag"):
    val result = testIndexCommand("index", "--index", "team", "--since", "0", "--watch", "--dry")
    result match
      case Right(opts: IndexOpts) =>
        expect(opts.watch == true && opts.dry == true && opts.index == Index.Team)
      case _ => failure("Expected IndexOpts")
