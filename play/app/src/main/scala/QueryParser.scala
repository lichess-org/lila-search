package lila.search

import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.json.JodaReads._
import org.joda.time.DateTime
import lila.search.game.Clocking
import lila.search.game.Sorting

object JsonParser {
  def parse(index: Index)(obj: JsObject): Option[Q] =
    index match {
      case Index("game")  => gameReader.reads(obj).asOpt
      case Index("forum") => forumReader.reads(obj).asOpt
      case Index("team")  => teamReader.reads(obj).asOpt
      case Index("study") => studyReader.reads(obj).asOpt
      case _              => None
    }

  implicit val teamReader: Reads[Team] = Json.reads[Team]

  implicit val sortingJsonReader: Reads[Sorting]           = Json.reads[Sorting]
  implicit val clockingJsonReader: Reads[Clocking]         = Json.reads[Clocking]
  implicit val dateTimeOrdering: Ordering[DateTime]        = Ordering.fromLessThan(_ isBefore _)
  implicit val dateRangeJsonReader: Reads[Range[DateTime]] = rangeJsonReader[DateTime]
  implicit val gameReader: Reads[Game]                     = Json.reads[Game]

  implicit val forumReader: Reads[Forum] = Json.reads[Forum]

  implicit val studyReader: Reads[Study] = Json.reads[Study]

  implicit def rangeJsonReader[A: Reads: Ordering]: Reads[Range[A]] =
    (
      (__ \ "a").readNullable[A] and
        (__ \ "b").readNullable[A]
    ) { (a, b) => Range(a, b) }

}
