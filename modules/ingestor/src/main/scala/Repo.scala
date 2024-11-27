package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*

import java.time.Instant

import Repo.Result

trait Repo[A]:
  def watch(since: Option[Instant]): fs2.Stream[IO, Result[A]]
  def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[A]]

object Repo:
  type SourceWithId[A] = (String, A)
  case class Result[A](toIndex: List[SourceWithId[A]], toDelete: List[Id], timestamp: Option[Instant])
