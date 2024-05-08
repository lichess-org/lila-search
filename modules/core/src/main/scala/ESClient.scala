package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.fields.ElasticField
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl, Index => ESIndex, Response }
import scala.concurrent.{ ExecutionContext, Future }
import com.sksamuel.elastic4s.requests.indexes.IndexResponse
import com.sksamuel.elastic4s.requests.delete.DeleteResponse
import com.sksamuel.elastic4s.requests.bulk.BulkResponse

case class JsonObject(json: String) extends AnyVal

case class Index(name: String) extends AnyVal {
  def toES: ESIndex = ESIndex(name)
}

trait ESClient[F[_]] {
  def search[A](index: Index, query: A, from: From, size: Size)(implicit q: Queryable[A]): F[SearchResponse]

  def count[A](index: Index, query: A)(implicit q: Queryable[A]): F[CountResponse]
  def store(index: Index, id: Id, obj: JsonObject): F[Response[IndexResponse]]
  def storeBulk(index: Index, objs: List[(String, JsonObject)]): F[Unit]
  def deleteOne(index: Index, id: Id): F[Response[DeleteResponse]]
  def deleteMany(index: Index, ids: List[Id]): F[Response[BulkResponse]]
  def putMapping(index: Index, fields: Seq[ElasticField]): F[Unit]
  def refreshIndex(index: Index): F[Unit]

}

object ESClient {
  def makeFuture(client: ElasticClient)(implicit ec: ExecutionContext): ESClient[Future] =
    new ESClientFuture(client)
}

final private class ESClientFuture(client: ElasticClient)(implicit ec: ExecutionContext)
    extends ESClient[Future] {

  private def toResult[A](response: Response[A]): Future[A] =
    response.fold[Future[A]](Future.failed(new Exception(response.error.reason)))(Future.successful)

  def search[A](index: Index, query: A, from: From, size: Size)(implicit
      q: Queryable[A]
  ): Future[SearchResponse] =
    client
      .execute {
        q.searchDef(query)(from, size)(index)
      }
      .flatMap(toResult)
      .map(SearchResponse.apply)

  def count[A](index: Index, query: A)(implicit q: Queryable[A]): Future[CountResponse] =
    client
      .execute {
        q.countDef(query)(index)
      }
      .flatMap(toResult)
      .map(CountResponse.apply)

  def store(index: Index, id: Id, obj: JsonObject): Future[Response[IndexResponse]] =
    client.execute {
      indexInto(index.name).source(obj.json).id(id.value)
    }

  def storeBulk(index: Index, objs: List[(String, JsonObject)]): Future[Unit] =
    if (objs.isEmpty) funit
    else
      client.execute {
        ElasticDsl.bulk {
          objs.map { case (id, obj) =>
            indexInto(index.name).source(obj.json).id(id)
          }
        }
      }.void

  def deleteOne(index: Index, id: Id): Future[Response[DeleteResponse]] =
    client.execute {
      deleteById(index.toES, id.value)
    }

  def deleteMany(index: Index, ids: List[Id]): Future[Response[BulkResponse]] =
    client.execute {
      ElasticDsl.bulk {
        ids.map { id =>
          deleteById(index.toES, id.value)
        }
      }
    }

  def putMapping(index: Index, fields: Seq[ElasticField]): Future[Unit] =
    dropIndex(index) >> client.execute {
      createIndex(index.name)
        .mapping(
          properties(fields).source(false) // all false
        )
        .shards(5)
        .replicas(0)
        .refreshInterval(Which.refreshInterval(index))
    }.void

  def refreshIndex(index: Index): Future[Unit] =
    client
      .execute {
        ElasticDsl.refreshIndex(index.name)
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
