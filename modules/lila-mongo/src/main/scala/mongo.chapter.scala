package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import chess.format.pgn.Tag
import io.circe.*
import mongo4cats.circe.*
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Aggregate, Filter }
import org.typelevel.log4cats.Logger

import Repo.*
import io.circe.derivation.Configuration

trait ChapterRepo:
  // Get chapters by their study ids, returning a list of chapters per study
  def byStudyIds(ids: List[String]): IO[Map[String, List[StudyChapterData]]]

case class StudyChapterData(
    id: String,
    studyId: String,
    name: String,
    tags: List[Tag],
    description: Option[String]
):

  def chapterName: Option[String] =
    if StudyChapterData.defaultNameRegex.matches(name) then None else Some(name)

  def relevantTags: List[String] = tags.collect:
    case t if StudyChapterData.relevantPgnTags.contains(t.name) => t.value

object StudyChapterData:

  private given Configuration = Configuration.default.withTransformMemberNames:
    case "id" => "_id"
    case other => other

  given Decoder[StudyChapterData] = Decoder.derivedConfigured[StudyChapterData]
  given Encoder[StudyChapterData] = new Encoder[StudyChapterData]:
    final def apply(a: StudyChapterData): Json = ??? // Not needed for now

  given Decoder[Tag] = Decoder.decodeString.emap: s =>
    s.split(":", 2) match
      case Array(name, value) => Tag(name, value).asRight
      case _ => "Invalid pgn tag $v".asLeft

  given Encoder[Tag] = Encoder.encodeString.contramap(t => s"${t.name.toString}:${t.value}")

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

    val name = "name"
    val studyId = "studyId"
    val tags = "tags"
    val description = "description"

  object Query:

    import Aggregate.*

    def aggregate(studyIds: List[String]): Aggregate =
      matchBy(Filter.in(F.studyId, studyIds))

  def apply(mongo: MongoDatabase[IO])(using Logger[IO]): IO[ChapterRepo] =
    mongo.getCollection("study_chapter_flat").map(apply)

  def apply(coll: MongoCollection)(using Logger[IO]): ChapterRepo = new:
    def byStudyIds(ids: List[String]): IO[Map[String, List[StudyChapterData]]] =
      coll
        .aggregateWithCodec[StudyChapterData](Query.aggregate(ids))
        .stream
        .compile
        .toList
        .flatTap(docs => Logger[IO].debug(s"Received ${docs.size} chapters for ${ids.size} studies"))
        .map: chapters =>
          chapters.groupBy(_.studyId)
