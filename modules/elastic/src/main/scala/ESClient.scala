package lila.search

import cats.MonadThrow
import cats.effect.*
import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.cats.effect.instances.*
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.{
  ElasticClient,
  ElasticDsl,
  ElasticProperties,
  Executor,
  Functor,
  Index as ESIndex,
  Indexable,
  Response
}

extension (index: Index)
  def toES: ESIndex = ESIndex(index.value)

  def mapping = index match
    case Index.Forum => forum.Mapping.fields
    case Index.Game  => game.Mapping.fields
    case Index.Study => study.Mapping.fields
    case Index.Team  => team.Mapping.fields

trait ESClient[F[_]]:

  def search[A](query: A, from: SearchFrom, size: SearchSize)(using Queryable[A]): F[List[Id]]
  def count[A](query: A)(using Queryable[A]): F[Long]
  def store[A](index: Index, id: Id, obj: A)(using Indexable[A]): F[Unit]
  def storeBulk[A](index: Index, objs: Seq[(String, A)])(using Indexable[A]): F[Unit]
  def deleteOne(index: Index, id: Id): F[Unit]
  def deleteMany(index: Index, ids: List[Id]): F[Unit]
  def putMapping(index: Index): F[Unit]
  def refreshIndex(index: Index): F[Unit]
  def status: F[String]

object ESClient:

  def apply(uri: String): Resource[IO, ESClient[IO]] =
    Resource
      .make(IO(ElasticClient(JavaClient(ElasticProperties(uri)))))(client => IO(client.close()))
      .map(ESClient.apply[IO])

  def apply[F[_]: MonadThrow: Functor: Executor](client: ElasticClient) = new ESClient[F]:

    def status: F[String] =
      client
        .execute(clusterHealth())
        .flatMap(toResult)
        .map(_.status)

    private def toResult[A](response: Response[A]): F[A] =
      response.fold(MonadThrow[F].raiseError[A](response.error.asException))(MonadThrow[F].pure)

    private def unitOrFail[A](response: Response[A]): F[Unit] =
      response.fold(MonadThrow[F].raiseError[Unit](response.error.asException))(_ => MonadThrow[F].unit)

    def search[A](query: A, from: SearchFrom, size: SearchSize)(using q: Queryable[A]): F[List[Id]] =
      client
        .execute(q.searchDef(query)(from, size))
        .flatMap(toResult)
        .map(_.hits.hits.toList.map(h => Id(h.id)))

    def count[A](query: A)(using q: Queryable[A]): F[Long] =
      client
        .execute(q.countDef(query))
        .flatMap(toResult)
        .map(_.count)

    def store[A](index: Index, id: Id, obj: A)(using indexable: Indexable[A]): F[Unit] =
      client
        .execute(indexInto(index.value).source(obj).id(id.value))
        .flatMap(unitOrFail)

    def storeBulk[A](index: Index, objs: Seq[(String, A)])(using indexable: Indexable[A]): F[Unit] =
      val request  = indexInto(index.value)
      val requests = bulk(objs.map((id, obj) => request.source(obj).id(id)))
      client
        .execute(requests)
        .flatMap(unitOrFail)
        .whenA(objs.nonEmpty)

    def deleteOne(index: Index, id: Id): F[Unit] =
      client
        .execute(deleteById(index.toES, id.value))
        .flatMap(unitOrFail)

    def deleteMany(index: Index, ids: List[Id]): F[Unit] =
      client
        .execute(bulk(ids.map(id => deleteById(index.toES, id.value))))
        .flatMap(unitOrFail)
        .whenA(ids.nonEmpty)

    def putMapping(index: Index): F[Unit] =
      dropIndex(index) >> client
        .execute:
          createIndex(index.value)
            .mapping(properties(index.mapping).source(false)) // all false
            .shards(5)
            .replicas(0)
            .refreshInterval(Which.refreshInterval(index))
        .flatMap(unitOrFail)

    def refreshIndex(index: Index): F[Unit] =
      client
        .execute(ElasticDsl.refreshIndex(index.value))
        .flatMap(unitOrFail)

    private def dropIndex(index: Index) =
      client.execute(deleteIndex(index.value))
