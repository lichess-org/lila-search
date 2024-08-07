package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import chess.format.pgn.Tag
import io.circe.*
import mongo4cats.bson.{ BsonValue, Document }
import mongo4cats.circe.*
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Accumulator, Aggregate, Filter }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

trait ChapterRepo:
  // Aggregate chapters data and convert them to StudyChapterText by their study ids
  def byStudyIds(ids: List[String]): IO[Map[String, String]]

case class ChapterData(
    _id: String,
    name: List[String],
    tags: List[List[Tag]],
    comments: List[List[List[String]]],
    description: List[String],
    conceal: List[Int],
    practice: List[Boolean],
    gamebook: List[Boolean]
) derives Codec.AsObject:
  def toStudyText: String =
    (conceal.map(_ => "conceal puzzle") ++
      practice.collect { case true => "practice" } ++
      gamebook.collect { case true => "gamebook" } ++
      name ++ relevantTags ++ comments.flatten.flatten ++ description)
      .mkString("", ", ", " ")

  def toPair = _id -> toStudyText

  def relevantTags = tags.flatten.collect {
    case t if ChapterData.relevantPgnTags.contains(t.name) => t.value
  }

object ChapterData:
  import io.circe.Decoder.decodeString
  import io.circe.Encoder.encodeString
  given Decoder[Tag] = decodeString.emap: s =>
    s.split(":", 2) match
      case Array(name, value) => Tag(name, value).asRight
      case _                  => "Invalid pgn tag $v".asLeft

  given Encoder[Tag] = encodeString.contramap(t => s"${t.name}:${t.value}")

  private val relevantPgnTags: Set[chess.format.pgn.TagType] = Set(
    Tag.Variant,
    Tag.Event,
    Tag.Round,
    Tag.White,
    Tag.Black,
    Tag.WhiteFideId,
    Tag.BlackFideId,
    Tag.ECO,
    Tag.Opening,
    Tag.Annotator
  )

object ChapterRepo:

  object F:

    val name        = "name"
    val studyId     = "studyId"
    val tags        = "tags"
    val conceal     = "conceal"
    val description = "description"
    val practice    = "practice"
    val gamebook    = "gamebook"

    // accumulates comments into a list
    val comments     = "comments"
    val commentTexts = "comments.v.co.text"

  object Query:

    import Aggregate.*
    import Accumulator.*

    val rootElementsAsArray = addFields(F.comments -> Document("$objectToArray" -> BsonValue.string("$root")))

    val groupBy = group(
      F.studyId.dollarPrefix,
      push(F.comments, F.commentTexts.dollarPrefix)
        .combinedWith(push(F.name, F.name.dollarPrefix))
        .combinedWith(push(F.tags, F.tags.dollarPrefix))
        .combinedWith(push(F.description, F.description.dollarPrefix))
        .combinedWith(push(F.gamebook, F.gamebook.dollarPrefix))
        .combinedWith(push(F.conceal, F.conceal.dollarPrefix))
        .combinedWith(push(F.practice, F.practice.dollarPrefix))
    )

    def aggregate(studyIds: List[String]): Aggregate =
      val filter = matchBy(Filter.in(F.studyId, studyIds))
      List(rootElementsAsArray, groupBy).fold(filter)(_.combinedWith(_))

  def apply(mongo: MongoDatabase[IO])(using Logger[IO]): IO[ChapterRepo] =
    mongo.getCollection("study_chapter_flat").map(apply)

  def apply(coll: MongoCollection)(using Logger[IO]): ChapterRepo = new:
    def byStudyIds(ids: List[String]): IO[Map[String, String]] =
      coll
        .aggregateWithCodec[ChapterData](Query.aggregate(ids))
        .stream
        .evalTap(x => debug"$x")
        .compile
        .toList
        .map(_.map(_.toPair).toMap)
