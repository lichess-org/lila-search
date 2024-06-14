package lila.search
package ingestor
import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sksamuel.elastic4s.Indexable
import lila.search.spec.{ ForumSource, Source }
import mongo4cats.bson.Document
import mongo4cats.collection.GenericMongoCollection
import mongo4cats.models.collection.ChangeStreamDocument
import smithy4s.json.Json.given
import smithy4s.schema.Schema

type MongoCollection = GenericMongoCollection[IO, Document, [A] =>> fs2.Stream[IO, A]]

extension [A](change: ChangeStreamDocument[A])
  def id: Option[String] = change.documentKey.flatMap(_.getString("_id"))

given [A: Schema]: Indexable[A] = (a: A) => writeToString(a)
given Indexable[Source] =
  _ match
    case f: Source.ForumCase => writeToString(f.forum)
    case g: Source.GameCase  => writeToString(g.game)
    case s: Source.StudyCase => writeToString(s.study)
    case t: Source.TeamCase  => writeToString(t.team)
