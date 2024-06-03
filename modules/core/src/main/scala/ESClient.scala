package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture as _, * }
import com.sksamuel.elastic4s.fields.ElasticField
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl, Index as ESIndex, Response }
import com.sksamuel.elastic4s.{ Executor, Functor, Indexable }
import cats.syntax.all.*
import cats.MonadThrow

case class Index(name: String) extends AnyVal:
  def toES: ESIndex = ESIndex(name)

trait ESClient[F[_]]:

  def search[A](index: Index, query: A, from: From, size: Size)(implicit q: Queryable[A]): F[SearchResponse]
  def count[A](index: Index, query: A)(implicit q: Queryable[A]): F[CountResponse]
  def store[A](index: Index, id: Id, obj: A)(implicit indexable: Indexable[A]): F[Unit]
  def storeBulk[A](index: Index, objs: Seq[(String, A)])(implicit indexable: Indexable[A]): F[Unit]
  def deleteOne(index: Index, id: Id): F[Unit]
  def deleteMany(index: Index, ids: List[Id]): F[Unit]
  def putMapping(index: Index, fields: Seq[ElasticField]): F[Unit]
  def refreshIndex(index: Index): F[Unit]
  def status: F[String]

object ESClient:

  def apply[F[_]: MonadThrow: Functor: Executor](client: ElasticClient) = new ESClient[F]:

    def status: F[String] =
      client
        .execute(ElasticDsl.clusterHealth())
        .flatMap(toResult)
        .map(_.status)

    def toResult[A](response: Response[A]): F[A] =
      response.fold(MonadThrow[F].raiseError[A](response.error.asException))(MonadThrow[F].pure)

    def search[A](index: Index, query: A, from: From, size: Size)(implicit
        q: Queryable[A]
    ): F[SearchResponse] =
      client
        .execute(q.searchDef(query)(from, size)(index))
        .flatMap(toResult)
        .map(SearchResponse.apply)

    def count[A](index: Index, query: A)(implicit q: Queryable[A]): F[CountResponse] =
      client
        .execute(q.countDef(query)(index))
        .flatMap(toResult)
        .map(CountResponse.apply)

    def store[A](index: Index, id: Id, obj: A)(implicit indexable: Indexable[A]): F[Unit] =
      client.execute(indexInto(index.name).source(obj).id(id.value)).void

    def storeBulk[A](index: Index, objs: Seq[(String, A)])(implicit indexable: Indexable[A]): F[Unit] =
      if objs.isEmpty then ().pure[F]
      else
        client.execute {
          ElasticDsl.bulk {
            objs.map { case (id, obj) =>
              indexInto(index.name).source(obj).id(id)
            }
          }
        }.void

    def deleteOne(index: Index, id: Id): F[Unit] =
      client.execute(deleteById(index.toES, id.value)).void

    def deleteMany(index: Index, ids: List[Id]): F[Unit] =
      client.execute {
        ElasticDsl.bulk {
          ids.map { id =>
            deleteById(index.toES, id.value)
          }
        }
      }.void

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
        .execute(ElasticDsl.refreshIndex(index.name))
        .void
        .recover { case _: Exception =>
          println(s"Failed to refresh index $index")
        }

    private def dropIndex(index: Index) =
      client.execute { deleteIndex(index.name) }
