package lila.search

import cats.syntax.all.*

import java.time.ZoneId

opaque type SearchDateTime = String

object SearchDateTime:

  def fromString(value: String): Either[String, SearchDateTime] =
    Either
      .catchNonFatal(formatter.parse(value))
      .leftMap(_ => s"`$value` is not a valid date format: $format")
      .as(value)

  def fromInstant(value: java.time.Instant): SearchDateTime =
    formatter.format(value)

  val format = "yyyy-MM-dd HH:mm:ss"
  val formatter = java.time.format.DateTimeFormatter.ofPattern(format).withZone(ZoneId.systemDefault())

  extension (x: SearchDateTime) def value: String = x

opaque type Id = String
object Id:
  def apply(value: String): Id = value
  extension (x: Id) def value: String = x

enum Index(val value: String):
  case Forum extends Index("forum")
  case Ublog extends Index("ublog")
  case Game extends Index("game")
  case Study extends Index("study")
  case Study2 extends Index("study2")
  case Team extends Index("team")

object Index:
  def fromString(value: String): Either[String, Index] =
    value match
      case "forum" => Index.Forum.asRight
      case "ublog" => Index.Ublog.asRight
      case "game" => Index.Game.asRight
      case "study" => Index.Study.asRight
      case "study2" => Index.Study2.asRight
      case "team" => Index.Team.asRight
      case _ => s"Invalid index: $value. It must be in ${Index.valuesStrings}".asLeft

  private def valuesStrings = Index.values.map(_.value).toList.mkString_("{", ", ", "}")

opaque type From = Int
object From:
  def apply(value: Int): From =
    if value >= 0 then value
    else 0

  extension (x: From) def value: Int = x

opaque type Size = Int
object Size:
  def apply(value: Int): Size =
    if value > 0 then value
    else 12

  extension (x: Size) def value: Int = x

extension (self: Boolean) def fold[A](t: => A, f: => A): A = if self then t else f
