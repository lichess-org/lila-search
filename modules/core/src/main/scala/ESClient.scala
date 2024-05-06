package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.fields.ElasticField
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl, Index => ESIndex, Response }
import play.api.libs.json._
import scala.concurrent.{ ExecutionContext, Future }

case class Index(name: String) extends AnyVal {
  def toES: ESIndex = ESIndex(name)
}

final class ESClient(client: ElasticClient)(implicit ec: ExecutionContext) {

  private def toResult[A](response: Response[A]): Future[A] =
    response.fold[Future[A]](Future.failed(new Exception(response.error.reason)))(Future.successful)

  def search(index: Index, query: Query, from: From, size: Size) =
    client execute {
      query.searchDef(from, size)(index)
    } flatMap toResult map SearchResponse.apply

  def count(index: Index, query: Query) =
    client execute {
      query.countDef(index)
    } flatMap toResult map CountResponse.apply

  def store(index: Index, id: Id, obj: JsObject) =
    client execute {
      indexInto(index.name) source Json.stringify(obj) id id.value
    }

  def storeBulk(index: Index, objs: JsObject) =
    if (objs.fields.isEmpty) funit
    else
      client execute {
        ElasticDsl.bulk {
          objs.fields.collect { case (id, JsString(doc)) =>
            indexInto(index.name) source doc id id
          }
        }
      }

  def deleteOne(index: Index, id: Id) =
    client execute {
      deleteById(index.toES, id.value)
    }

  def deleteMany(index: Index, ids: List[Id]) =
    client execute {
      ElasticDsl.bulk {
        ids.map { id =>
          deleteById(index.toES, id.value)
        }
      }
    }

  def putMapping(index: Index, fields: Seq[ElasticField]) =
    dropIndex(index) >> client.execute {
      createIndex(index.name).mapping(
        properties(fields) source false // all false
      ) shards 5 replicas 0 refreshInterval Which.refreshInterval(index)
    }

  def refreshIndex(index: Index) =
    client
      .execute {
        ElasticDsl refreshIndex index.name
      }
      .void
      .recover { case _: Exception =>
        println(s"Failed to refresh index $index")
      }

  private def dropIndex(index: Index) =
    client.execute {
      deleteIndex(index.name)
    }
}
