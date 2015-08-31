package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._

object Team {
  val name = "na"
  val description = "de"
  val location = "lo"
  val nbMembers = "nbm"

  def mapping = Seq(
    name typed StringType boost 3,
    description typed StringType boost 2,
    location typed StringType,
    nbMembers typed ShortType)
}
