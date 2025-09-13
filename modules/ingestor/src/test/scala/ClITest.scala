package lila.search
package ingestor

import cats.syntax.all.*
import com.monovore.decline

import java.time.Instant

import opts.*

object CLITest extends weaver.FunSuite:

  def testCommand(args: String*) =
    decline.Command("test", "Test Command")(opts.parse).parse(args)

  test("index command"):
    expect.same(
      testCommand("index", "--index", "team", "--since", "0", "--until", "1", "--dry"),
      IndexOpts(Index.Team, Instant.ofEpochSecond(0), Instant.ofEpochSecond(1), false, true).asRight
    )

  test("index command with reingest-all960"):
    expect.same(
      testCommand("index", "--index", "team", "--since", "0", "--until", "1", "--reingest-all960", "--dry"),
      IndexOpts(Index.Team, Instant.ofEpochSecond(0), Instant.ofEpochSecond(1), true, true).asRight
    )

  test("watch command"):
    expect.same(
      testCommand("watch", "--index", "team", "--since", "0", "--dry"),
      WatchOpts(Index.Team, Instant.ofEpochSecond(0), true).asRight
    )
