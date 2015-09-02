package lila.search

object Chronometer {

  def apply[A](name: String)(f: => Fu[A]): Fu[A] = {
    val start = nowMillis
    // logger debug s"$name - start"
    f andThen {
      case scala.util.Failure(e: Exception) => logger warn s"$name - failed in ${nowMillis - start}ms - ${e.getMessage}"
      case scala.util.Failure(e)            => throw e // Throwables
      case scala.util.Success(_)            => logger debug s"$name in ${nowMillis - start}ms"
    }
  }

  private def nowMillis = System.currentTimeMillis

  private lazy val logger = play.api.Logger("chrono")
}
