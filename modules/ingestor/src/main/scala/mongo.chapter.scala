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

import Repo.*

trait ChapterRepo:
  // Aggregate chapters data and convert them to StudyChapterText by their study ids
  def byStudyIds(ids: List[String]): IO[Map[String, StudyData]]

case class StudyData(
    _id: String,
    name: List[String],
    tags: List[List[Tag]],
    comments: List[List[List[String]]],
    description: List[String],
    conceal: List[Int],
    practice: List[Boolean],
    gamebook: List[Boolean]
) derives Codec.AsObject:
  def chapterTexts: String =
    (conceal.map(_ => "conceal puzzle") ++
      practice.collect { case true => "practice" } ++
      gamebook.collect { case true => "gamebook" } ++
      relevantTags ++ comments.flatten.flatten ++ description)
      .mkString("", ", ", " ")

  def chapterNames = name
    .collect { case c if !StudyData.defaultNameRegex.matches(c) => c }
    .mkString(" ")

  def relevantTags = tags.flatten.collect:
    case t if StudyData.relevantPgnTags.contains(t.name) => t.value

object StudyData:

  given Decoder[Tag] = Decoder.decodeString.emap: s =>
    s.split(":", 2) match
      case Array(name, value) => Tag(name, value).asRight
      case _                  => "Invalid pgn tag $v".asLeft

  given Encoder[Tag] = Encoder.encodeString.contramap(t => s"${t.name}:${t.value}")

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

  private val defaultNameRegex = """Chapter \d+""".r

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
    def byStudyIds(ids: List[String]): IO[Map[String, StudyData]] =
      coll
        .aggregateWithCodec[StudyData](Query.aggregate(ids))
        .all
        .flatTap(docs => Logger[IO].debug(s"Received $docs chapters"))
        .map(_.map(x => x._id -> x).toMap)
