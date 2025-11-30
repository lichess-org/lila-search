package lila.search
package ingestor

import cats.effect.IO

import java.time.Instant

trait Repo[A]:
  def watch(since: Option[Instant]): fs2.Stream[IO, Repo.Result[A]]
  def fetchAll(since: Instant, until: Instant): fs2.Stream[IO, Repo.Result[A]]
  def fetchUpdate(since: Instant, until: Instant): fs2.Stream[IO, List[A]] =
    fetchAll(since, until).map(_.toIndex)
  def fetchDelete(since: Instant, until: Instant): fs2.Stream[IO, List[Id]] =
    fetchAll(since, until).map(_.toDelete)

object Repo:
  type ToUpdate = List[(Id, Map[String, Any])]
  case class Result[A](
      toIndex: List[A],
      toDelete: List[Id],
      timestamp: Option[Instant]
  )

  import cats.effect.IO
  import mongo4cats.bson.Document
  import mongo4cats.collection.GenericMongoCollection
  import mongo4cats.models.collection.ChangeStreamDocument
  import mongo4cats.operations.Filter
  import org.bson.BsonTimestamp

  import java.time.Instant

  val _id = "_id"

  type MongoCollection = GenericMongoCollection[IO, Document, [A] =>> fs2.Stream[IO, A]]

  given [A] => HasDocId[ChangeStreamDocument[A]]:
    extension (change: ChangeStreamDocument[A])
      def docId: Option[String] =
        change.documentKey.flatMap(_.id)

  extension (doc: Document)
    def id: Option[String] =
      doc.getString(_id)

  extension (instant: Instant)
    inline def asBsonTimestamp: BsonTimestamp = BsonTimestamp(instant.getEpochSecond.toInt, 1)

  def range(field: String)(since: Instant, until: Option[Instant]): Filter =
    inline def gtes = Filter.gte(field, since)
    until.fold(gtes)(until => gtes.and(Filter.lt(field, until)))

  extension (s: String) def dollarPrefix = "$" + s
