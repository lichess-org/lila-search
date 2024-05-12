package lila

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

package object search {

  object Date {
    import org.joda.time.format.{ DateTimeFormat, DateTimeFormatter }
    val format                       = "yyyy-MM-dd HH:mm:ss"
    val formatter: DateTimeFormatter = DateTimeFormat.forPattern(format)
  }

  // fix scala

  type Fu[+A] = Future[A]
  type Funit  = Fu[Unit]

  def fuccess[A](a: A)                       = Future.successful(a)
  def fufail[A <: Throwable, B](a: A): Fu[B] = Future.failed(a)
  def fufail[A](a: String): Fu[A]            = fufail(new Exception(a))
  val funit                                  = fuccess(())

  implicit final class LilaPimpedFuture[A](fua: Fu[A]) {

    def >>-(sideEffect: => Unit)(implicit ec: ExecutionContext): Fu[A] =
      fua.andThen { case _ =>
        sideEffect
      }

    def >>[B](fub: => Fu[B])(implicit ec: ExecutionContext): Fu[B] = fua.flatMap(_ => fub)

    def void: Funit = fua.map(_ => ())(ExecutionContext.parasitic)

    def inject[B](b: => B)(implicit ec: ExecutionContext): Fu[B] = fua.map(_ => b)
  }

  implicit class LilaPimpedBoolean(self: Boolean) {

    def fold[A](t: => A, f: => A): A = if (self) t else f

    def option[A](a: => A): Option[A] = if (self) Some(a) else None
  }

}
