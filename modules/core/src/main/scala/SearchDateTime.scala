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

opaque type Index = String
object Index:
  extension (x: Index) def value: String = x
  def apply(value: String): Index        = value

opaque type SearchFrom = Int
object SearchFrom:
  def apply(value: Int): Either[String, SearchFrom] =
    Either.cond(value >= 0, value, "From must be greater than or equal to 0")

  extension (x: SearchFrom) def value: Int = x

opaque type SearchSize = Int
object SearchSize:
  def apply(value: Int): Either[String, SearchSize] =
    Either.cond(value > 0, value, "Size must be greater than 0")

  extension (x: SearchSize) def value: Int = x
