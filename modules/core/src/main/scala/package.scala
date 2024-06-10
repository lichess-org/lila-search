package lila

package object search:

  object Date:
    import org.joda.time.format.{ DateTimeFormat, DateTimeFormatter }
    val format                       = "yyyy-MM-dd HH:mm:ss"
    val formatter: DateTimeFormatter = DateTimeFormat.forPattern(format)

  extension (self: Boolean) def fold[A](t: => A, f: => A): A = if self then t else f
