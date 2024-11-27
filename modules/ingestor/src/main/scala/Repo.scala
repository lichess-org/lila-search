package lila.search
package ingestor

import cats.effect.IO

import java.time.Instant

trait Repo[A]:
  def watch(since: Option[Instant]): fs2.Stream[IO, Repo.Result[A]]
  def fetch(since: Instant, until: Instant): fs2.Stream[IO, Repo.Result[A]]

object Repo:
  type SourceWithId[A] = (String, A)
  case class Result[A](toIndex: List[SourceWithId[A]], toDelete: List[Id], timestamp: Option[Instant])
