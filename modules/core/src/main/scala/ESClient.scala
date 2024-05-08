package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.fields.ElasticField
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl, Index => ESIndex, Response }
import com.sksamuel.elastic4s.requests.indexes.IndexResponse
import com.sksamuel.elastic4s.requests.delete.DeleteResponse
import com.sksamuel.elastic4s.requests.bulk.BulkResponse
import com.sksamuel.elastic4s.{ Executor, Functor }
import cats.syntax.all.*
import cats.MonadThrow

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

  def apply[F[_]: MonadThrow: Functor: Executor](client: ElasticClient) = new ESClient[F] {

    def toResult[A](response: Response[A]): F[A] =
      response
        .fold[F[A]](MonadThrow[F].raiseError[A](new Exception(response.error.reason)))(MonadThrow[F].pure)

    def search[A](index: Index, query: A, from: From, size: Size)(implicit
        q: Queryable[A]
    ): F[SearchResponse] =
      client
        .execute { q.searchDef(query)(from, size)(index) }
        .flatMap(toResult)
        .map(SearchResponse.apply)

    def count[A](index: Index, query: A)(implicit q: Queryable[A]): F[CountResponse] =
      client
        .execute {
          q.countDef(query)(index)
        }
        .flatMap(toResult)
        .map(CountResponse.apply)

    def store(index: Index, id: Id, obj: JsonObject): F[Response[IndexResponse]] =
      client.execute {
        indexInto(index.name).source(obj.json).id(id.value)
      }

    def storeBulk(index: Index, objs: List[(String, JsonObject)]): F[Unit] =
      if (objs.isEmpty) ().pure[F]
      else
        client.execute {
          ElasticDsl.bulk {
            objs.map { case (id, obj) =>
              indexInto(index.name).source(obj.json).id(id)
            }
          }
        }.void

    def deleteOne(index: Index, id: Id): F[Response[DeleteResponse]] =
      client.execute {
        deleteById(index.toES, id.value)
      }

    def deleteMany(index: Index, ids: List[Id]): F[Response[BulkResponse]] =
      client.execute {
        ElasticDsl.bulk {
          ids.map { id =>
            deleteById(index.toES, id.value)
          }
        }
      }

    def putMapping(index: Index, fields: Seq[ElasticField]): F[Unit] =
      dropIndex(index) >> client.execute {
        createIndex(index.name)
          .mapping(
            properties(fields).source(false) // all false
          )
          .shards(5)
          .replicas(0)
          .refreshInterval(Which.refreshInterval(index))
      }.void

    def refreshIndex(index: Index): F[Unit] =
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
}
