package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._

object Game {
  val status = "s"
  val turns = "t"
  val rated = "r"
  val variant = "v"
  val uids = "u"
  val winner = "w"
  val winnerColor = "c"
  val averageRating = "a"
  val ai = "i"
  val opening = "o"
  val date = "d"
  val duration = "l"
  val analysed = "n"
  val whiteUser = "wu"
  val blackUser = "bu"

  def mapping = Seq(
    status typed ShortType,
    turns typed ShortType,
    rated typed BooleanType,
    variant typed ShortType,
    uids typed StringType,
    winner typed StringType,
    winnerColor typed ShortType,
    averageRating typed ShortType,
    ai typed ShortType,
    opening typed StringType,
    date typed DateType format Date.format,
    duration typed IntegerType,
    analysed typed BooleanType,
    whiteUser typed StringType,
    blackUser typed StringType
  ).map(_ index "not_analyzed")
}
