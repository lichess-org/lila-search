package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.FieldType._

object Forum {
  val body = "bo"
  val topic = "to"
  val topicId = "ti"
  val author = "au"
  val staff = "st"
  val troll = "tr"
  val date = "da"

  def mapping = Seq(
    body typed StringType boost 2,
    topic typed StringType boost 4,
    author typed StringType index "not_analyzed",
    topicId typed StringType,
    staff typed BooleanType,
    troll typed BooleanType,
    date typed DateType)
}
