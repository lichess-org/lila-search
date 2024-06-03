package lila.search
package spec

import cats.syntax.all.*
import smithy4s.*

import java.time.ZoneId

opaque type SearchDateTime = String

object SearchDateTime:
  extension (x: SearchDateTime) def value: String = x

  def fromString(value: String): Either[String, SearchDateTime] =
    Either
      .catchNonFatal(formatter.parse(value))
      .leftMap(_ => s"`$value` is not a valid date format: $format")
      .as(value)

  def fromInstant(value: java.time.Instant): SearchDateTime =
    formatter.format(value)

  given RefinementProvider[DateTimeFormat, String, SearchDateTime] =
    Refinement.drivenBy(SearchDateTime.fromString, _.value)

  val format    = "yyyy-MM-dd HH:mm:ss"
  val formatter = java.time.format.DateTimeFormatter.ofPattern(format).withZone(ZoneId.systemDefault())
