package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import com.monovore.decline.*
import com.monovore.decline.effect.*

object cli
    extends CommandIOApp(
      name = "lila-search-cli",
      header = "CLI for lila-search",
      version = "3.0.0"
    ):

  override def main: Opts[IO[ExitCode]] =
    indexOpts.map(x => IO.println(x).as(ExitCode.Success))

  val indexOpts =
    Opts
      .option[String]("index", "Specify the targeted index", metavar = "string")
      .mapValidated(x => Validated.fromEither(Index.fromString(x)).toValidatedNel)
