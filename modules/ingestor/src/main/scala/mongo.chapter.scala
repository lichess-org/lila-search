package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import mongo4cats.bson.{ BsonValue, Document }
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Accumulator, Aggregate, Filter }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

opaque type ChapterText = String
object ChapterText:
  def apply(value: String): ChapterText        = value
  extension (c: ChapterText) def value: String = c

opaque type StudyChapterText = String
object StudyChapterText:
  def apply(value: String): StudyChapterText        = value
  extension (c: StudyChapterText) def value: String = c

trait ChapterRepo:
  // Aggregate chapters data and convert them to StudyChapterText by their study ids
  def byStudyIds(ids: List[String]): IO[Map[String, StudyChapterText]]

case class ChapterData(names: List[String], tags: List[String], comments: List[String]):
  def toStudyText: StudyChapterText = StudyChapterText:
    (names ++ tags ++ comments).mkString(" ")

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
    def byStudyIds(ids: List[String]): IO[Map[String, StudyChapterText]] =
      coll
        .aggregate[Document](Query.aggregate(ids))
        .stream
        .evalTap(x => debug"$x")
        .parEvalMapUnordered(50)(fromDoc) // parEvalMap
        .evalTap(x => debug"$x")
        .compile
        .toList
        .map(_.flatten.toMap)

    def withStudyId(studyId: String, names: List[String], tags: List[String], comments: List[String]) =
      studyId -> ChapterData(names, tags, comments).toStudyText

    def fromDoc(doc: Document)(using Logger[IO]): IO[Option[(String, StudyChapterText)]] =
      val studyId = doc.id
      val names   = doc.getNestedListOrEmpty(F.name)
      // TODO filter meaning tags only
      val tags         = doc.getNestedListOrEmpty(F.tags)
      val comments     = doc.getNestedListOrEmpty(F.comments)
      val descriptions = doc.getListOfStringOrEmpty(F.description)
      val conceal: Option[List[String]] =
        doc.getList(F.conceal).map(_.flatten(_.asInt).map(_ => "conceal puzzle"))
      val gamebook =
        doc.getList(F.gamebook).map(_.flatten(_.asBoolean).collect { case true => "gamebook" })
      val practice =
        doc.getList(F.practice).map(_.flatten(_.asBoolean).collect { case true => "practice" })

      val studyText = StudyChapterText:
        (conceal ++ gamebook ++ practice ++ names ++ tags ++ comments ++ descriptions)
          .mkString("", ", ", " ")
      studyId.map(_ -> studyText).pure[IO]

  extension (doc: Document)
    def getNestedListOrEmpty(field: String): List[String] =
      doc.getList(field).map(_.flatMap(_.asList).flatten.flatMap(_.asString)).getOrElse(Nil)

    def getKNestedListOrEmpty(k: Int)(field: String) =
      doc.getList(field).map(_.flatMap(_.asList).flatten.flatMap(_.asString)).getOrElse(Nil)

    def getListOfStringOrEmpty(field: String) =
      doc.getList(F.name).map(_.flatten(_.asString)).getOrElse(Nil)
