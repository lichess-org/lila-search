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

    val unwinding = unwind(F.comments.dollarPrefix)
    val matching  = matchBy(Filter.exists(F.commentTexts))

    val groupBy = group(
      F.studyId.dollarPrefix,
      push(F.comments, F.commentTexts.dollarPrefix)
        .combinedWith(addToSet(F.name, F.name.dollarPrefix))
        .combinedWith(addToSet(F.tags, F.tags.dollarPrefix))
        .combinedWith(addToSet(F.description, F.description.dollarPrefix))
        .combinedWith(addToSet(F.gamebook, F.gamebook.dollarPrefix))
        .combinedWith(addToSet(F.conceal, F.conceal.dollarPrefix))
        .combinedWith(addToSet(F.practice, F.practice.dollarPrefix))
    )

    def aggregate(studyIds: List[String]): Aggregate =
      val filter = matchBy(Filter.in(F.studyId, studyIds))
      List(rootElementsAsArray, unwinding, matching, groupBy).fold(filter)(_.combinedWith(_))

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
      val names   = doc.getNestedListOrEmpty(F.name).mkString(" ")
      // TODO filter meaning tags only
      val tags         = doc.getNestedListOrEmpty(F.tags).mkString(" ")
      val comments     = doc.getNestedListOrEmpty(F.comments).mkString(" ")
      val descriptions = doc.getListOfStringOrEmpty(F.description).mkString(" ")
      val conceal =
        doc.getList(F.conceal).map(_.flatten(_.asBoolean).nonEmpty).map(_.fold("conceal puzzle", ""))
      val gamebook =
        doc.getList(F.gamebook).map(_.flatten(_.asBoolean).exists(identity)).map(_.fold("lesson", ""))
      val practice =
        doc.getList(F.practice).map(_.flatten(_.asBoolean).exists(identity)).map(_.fold("practice", ""))
      val studyText = StudyChapterText:
        (List(conceal, gamebook, practice).flatten ++ List(names, tags, comments, descriptions))
          .mkString(" ")
      studyId.map(_ -> studyText).pure[IO]

  extension (doc: Document)
    def getNestedListOrEmpty(field: String) =
      doc.getList(field).map(_.flatMap(_.asList).flatten.flatMap(_.asString)).getOrElse(Nil)

    def getListOfStringOrEmpty(field: String) =
      doc.getList(F.name).map(_.flatten(_.asString)).getOrElse(Nil)
