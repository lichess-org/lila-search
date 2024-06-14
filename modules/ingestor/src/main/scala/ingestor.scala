package lila.search
package ingestor

import cats.effect.*
import mongo4cats.bson.BsonValue
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Aggregate, Filter }
import org.bson.{ BsonDocument, BsonTimestamp }

trait Ingestor:
  def run(): IO[Unit]

object Ingestor:
  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO]): Ingestor = new:

    val eventFilter = Filter.in("operationType", List("replace", "insert"))
    val aggregate   = Aggregate.matchBy(eventFilter)
    val resumeToken = BsonDocument(
      "_data",
      BsonValue
        .string(
          "82666AB9B1000000012B022C0100296E5A1004AC00875A70284DF9B1268E0E8EC36E5E463C5F6964003C6D446144686A6D38000004"
        )
        .asJava
    )

    val startOperationalTime = BsonTimestamp(1718270441, 1)

    def run(): IO[Unit] =
      mongo
        .getCollection("f_post")
        .flatMap(
          _.watch(aggregate)
            // .resumeAfter(resumeToken)
            .startAtOperationTime(startOperationalTime)
            .stream
            .evalMap: event =>
              IO.println(event)
            //   s"resumeToken: ${event.resumeToken} type: ${event.operationType} key: ${event.documentKey} time: ${event.clusterTime} full: ${event.fullDocument} fullbefore: ${event.fullDocumentBeforeChange}"
            // )
            .compile
            .drain
        )
