package lila.search

import cats.MonadThrow
import cats.effect.kernel.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.http4s.Http4sClient
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl, ElasticError, Index as ESIndex, Indexable }
import fs2.io.file.Files
import org.http4s.Uri
import org.http4s.client.Client

trait ESClient[F[_]]:
  type RaiseF[A] = Raise[F, ElasticError] ?=> F[A]

  def search[A](query: A, from: From, size: Size)(using Queryable[A]): RaiseF[List[Id]]
  def count[A](query: A)(using Queryable[A]): RaiseF[Long]
  def store[A](index: Index, id: Id, obj: A)(using Indexable[A]): RaiseF[Unit]
  def storeBulk[A](index: Index, objs: Seq[A])(using Indexable[A], HasStringId[A]): RaiseF[Unit]
  def update[A](index: Index, id: Id, map: Map[String, Any]): RaiseF[Unit]
  def updateBulk[A](index: Index, docs: List[(Id, Map[String, Any])]): RaiseF[Unit]
  def deleteOne(index: Index, id: Id): RaiseF[Unit]
  def deleteMany(index: Index, ids: List[Id]): RaiseF[Unit]
  def putMapping(index: Index): RaiseF[Unit]
  def refreshIndex(index: Index): RaiseF[Unit]
  def status: RaiseF[String]
  def indexExists(index: Index): RaiseF[Boolean]

object ESClient:

  def apply[F[_]: Sync: Files](uri: Uri)(client: Client[F]): ESClient[F] =
    apply(ElasticClient(new Http4sClient(client, uri)))

  def apply[F[_]: MonadThrow](client: ElasticClient[F]) = new ESClient[F]:

    def status: RaiseF[String] =
      client
        .execute(clusterHealth())
        .flatMap(_.toResult)
        .map(_.status)

    def search[A](query: A, from: From, size: Size)(using Queryable[A]): RaiseF[List[Id]] =
      client
        .execute(query.searchDef(from, size))
        .flatMap(_.toResult)
        .map(_.hits.hits.toList.map(h => Id(h.id)))

    def count[A](query: A)(using Queryable[A]): RaiseF[Long] =
      client
        .execute(query.countDef)
        .flatMap(_.toResult)
        .map(_.count)

    def store[A](index: Index, id: Id, obj: A)(using Indexable[A]): RaiseF[Unit] =
      client
        .execute(indexInto(index.value).source(obj).id(id.value))
        .flatMap(_.unitOrFail)

    def storeBulk[A](index: Index, objs: Seq[A])(using Indexable[A], HasStringId[A]): RaiseF[Unit] =
      val request = indexInto(index.value)
      val requests = bulk(objs.map(source => request.source(source).id(source.id)))
      client
        .execute(requests)
        .flatMap(_.unitOrFail)
        .whenA(objs.nonEmpty)

    def update[A](index: Index, id: Id, map: Map[String, Any]): RaiseF[Unit] =
      client
        .execute(updateById(index.toES, id.value).doc(map))
        .flatMap(_.unitOrFail)

    def updateBulk[A](index: Index, docs: List[(Id, Map[String, Any])]): RaiseF[Unit] =
      client
        .execute(bulk(docs.map { case (id, map) => updateById(index.toES, id.value).doc(map) }))
        .flatMap(_.unitOrFail)
        .whenA(docs.nonEmpty)

    def deleteOne(index: Index, id: Id): RaiseF[Unit] =
      client
        .execute(deleteById(index.toES, id.value))
        .flatMap(_.unitOrFail)

    def deleteMany(index: Index, ids: List[Id]): RaiseF[Unit] =
      client
        .execute(bulk(ids.map(id => deleteById(index.toES, id.value))))
        .flatMap(_.unitOrFail)
        .whenA(ids.nonEmpty)

    def putMapping(index: Index): RaiseF[Unit] =
      dropIndex(index) *> client
        .execute:
          val request =
            createIndex(index.value)
              .mapping(properties(index.mapping).source(index.keepSource))
              .shards(5)
              .replicas(0)
              .refreshInterval(index.refreshInterval)
          index.analysis.fold(request)(request.analysis(_))
        .flatMap(_.unitOrFail)

    def refreshIndex(index: Index): RaiseF[Unit] =
      client
        .execute(ElasticDsl.refreshIndex(index.value))
        .flatMap(_.unitOrFail)

    def indexExists(index: Index): RaiseF[Boolean] =
      client
        .execute(ElasticDsl.indexExists(index.value))
        .flatMap(_.toResult)
        .map(_.exists)

    private def dropIndex(index: Index) =
      client.execute(deleteIndex(index.value))
