package lila.search
package ingestor

import cats.effect.IO

trait Ingestor[A]:
  def ingest(stream: fs2.Stream[IO, Repo.Result[A]]): IO[Unit]
