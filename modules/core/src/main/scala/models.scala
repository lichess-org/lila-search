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

  val format    = "yyyy-MM-dd HH:mm:ss"
  val formatter = java.time.format.DateTimeFormatter.ofPattern(format).withZone(ZoneId.systemDefault())

  extension (x: SearchDateTime) def value: String = x

opaque type Id = String
object Id:
  def apply(value: String): Id        = value
  extension (x: Id) def value: String = x

enum Index(val value: String):
  case Forum extends Index("forum")
  case Game  extends Index("game")
  case Study extends Index("study")
  case Team  extends Index("team")

object Index:
  def fromString(value: String): Either[String, Index] =
    value match
      case "forum" => Index.Forum.asRight
      case "game"  => Index.Game.asRight
      case "study" => Index.Study.asRight
      case "team"  => Index.Team.asRight
      case _       => s"Invalid index: $value".asLeft

opaque type SearchFrom = Int
object SearchFrom:
  def apply(value: Int): SearchFrom =
    if value >= 0 then value
    else 0

  extension (x: SearchFrom) def value: Int = x

opaque type SearchSize = Int
object SearchSize:
  def apply(value: Int): SearchSize =
    if value > 0 then value
    else 12

  extension (x: SearchSize) def value: Int = x
