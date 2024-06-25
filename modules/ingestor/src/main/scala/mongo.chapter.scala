package lila.search
package ingestor

import cats.effect.IO
import cats.kernel.Monoid
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
  // Fetches chapter names by their study ids
  // could be stream it's too large
  def byStudyIds(ids: List[String]): IO[Map[String, StudyChapterText]]

case class ChapterData(name: String, tags: List[String], comments: List[String]):
  def toStudyText: StudyChapterText = StudyChapterText:
    s"$name ${tags.mkString(" ")} ${comments.mkString(" ")}"

object ChapterData:
  // todo could be Semigroup as Map[K, Semigroup[V]] is a monoid
  given Monoid[ChapterData] = new:
    def empty: ChapterData = ChapterData("", List.empty, List.empty)
    def combine(x: ChapterData, y: ChapterData): ChapterData =
      ChapterData(x.name + " " + y.name, x.tags ++ y.tags, x.comments ++ y.comments)

object ChapterRepo:

  object F:

    val name    = "name"
    val studyId = "studyId"
    val tags    = "tags"

    // accumulates comments into a list
    val comments     = "comments"
    val commentTexts = "comments.v.co.text"

  val addFields =
    Aggregate.addFields(F.comments -> Document.empty.add("$objectToArray" -> "$root").toBsonDocument)

  val unwinding = Aggregate.unwind(F.comments.dollarPrefix)
  val matching  = Aggregate.matchBy(Filter.exists(F.commentTexts))
  val replaceRoot = Aggregate.replaceWith(
    Document.empty.add(
      "$mergeObjects" -> List(
        Document(
          _id        -> BsonValue.string(_id.dollarPrefix),
          F.studyId  -> BsonValue.string(F.studyId.dollarPrefix),
          F.name     -> BsonValue.string(F.name.dollarPrefix),
          F.tags     -> BsonValue.string(F.tags.dollarPrefix),
          F.comments -> BsonValue.string(F.commentTexts.dollarPrefix)
        )
      )
    )
  )

  // TODO maybe group by studyId as We accumulate all chapter data
  val group = Aggregate.group(
    _id,
    Accumulator
      .push(F.comments, F.comments.dollarPrefix)
      .combinedWith(Accumulator.first(F.name, F.name.dollarPrefix))
      .combinedWith(Accumulator.first(F.tags, F.tags.dollarPrefix))
      .combinedWith(Accumulator.first(F.studyId, F.studyId.dollarPrefix))
  )

  def apply(mongo: MongoDatabase[IO])(using Logger[IO]): IO[ChapterRepo] =
    mongo.getCollection("study_chapter_flat").map(apply)

  def apply(coll: MongoCollection)(using Logger[IO]): ChapterRepo = new:
    def byStudyIds(ids: List[String]): IO[Map[String, StudyChapterText]] =

      val filterByIds = Aggregate.matchBy(Filter.in(F.studyId, ids))
      val chapterAggregates = List(addFields, unwinding, matching, replaceRoot, group)
        .foldLeft(filterByIds)(_.combinedWith(_))

      for
        _ <- IO.println(chapterAggregates)
        _ <- IO.println(ids)
        x <- coll
          .aggregate[Document](chapterAggregates)
          .stream
          .evalTap(IO.println)
          .evalMap(fromDoc) // parEvalMap
          .evalTap(IO.println)
          .map(Map.from)
          .compile[IO, IO, Map[String, ChapterData]]
          .foldMonoid
          .map(_.view.mapValues(_.toStudyText).toMap)
        _ <- IO.println(x)
      yield x

    def withStudyId(
        studyId: String,
        name: String,
        tags: List[String],
        comments: List[String]
    ): (String, ChapterData) =
      studyId -> ChapterData(name, tags, comments)

    def fromDoc(doc: Document)(using Logger[IO]): IO[Option[(String, ChapterData)]] =
      val studyId = doc.getString(F.studyId)
      val name    = doc.getString(F.name)
      // TODO filter meaning tags only
      val tags     = doc.getList(F.tags).map(_.flatMap(_.asString))
      val comments = doc.getList(F.comments).map(_.flatMap(_.asList).flatten.flatMap(_.asString))
      (studyId, name, tags, comments)
        .mapN(withStudyId)
        .pure[IO]
        .flatTap: x =>
          def reason = studyId.fold("missing studyId")(_ => "") + tags.fold("missing tags")(_ => "")
            + comments.fold("missing comments")(_ => "") + name.fold("missing name")(_ => "")
          info"failed to get chapter data from $doc becaues $reason".whenA(x.isEmpty)
