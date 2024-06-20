package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*

import java.time.Instant

// supported flags: dry, verbose
object cli
    extends CommandIOApp(
      name = "lila-search-cli",
      header = "CLI for lila-search",
      version = "3.0.0"
    ):

  override def main: Opts[IO[ExitCode]] =
    opts.indexOpt.map(x => IO.println(x).as(ExitCode.Success))

object opts:
  case class IndexConfig(index: Index, since: Instant, until: Instant)

  // valid since <= until
  val indexOpt = (
    Opts.option[Index]("index", "Index to reindex", short = "i", metavar = "index"),
    Opts.option[Instant]("since", "Start date", short = "s", metavar = "time in epoch seconds"),
    Opts.option[Instant]("until", "End date", short = "u", metavar = "time in epoch seconds")
  ).mapN(IndexConfig.apply)

  given Argument[Index] =
    Argument.from("index")(x => Validated.fromEither(Index.fromString(x)).toValidatedNel)

  given Argument[Instant] =
    Argument.from("time in epoch seconds")(str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
    )
