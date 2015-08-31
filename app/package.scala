package lila

import scala.concurrent.Future

package object search {

  type Fu[+A] = Future[A]
  type Funit = Fu[Unit]

  def fuccess[A](a: A) = Future successful a
  def fufail[A <: Throwable, B](a: A): Fu[B] = Future failed a
  def fufail[A](a: String): Fu[A] = fufail(new Exception(a))
  val funit = fuccess(())

  implicit final class LilaPimpedFuture[A](fua: Fu[A]) {

    def >>-(sideEffect: => Unit): Fu[A] = fua andThen {
      case _ => sideEffect
    }

    def >>[B](fub: => Fu[B]): Fu[B] = fua flatMap (_ => fub)

    def void: Funit = fua map (_ => Unit)

    def inject[B](b: => B): Fu[B] = fua map (_ => b)
  }

  implicit def execontext = play.api.libs.concurrent.Execution.defaultContext
}
