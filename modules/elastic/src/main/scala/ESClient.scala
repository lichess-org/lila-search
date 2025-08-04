package lila.search

import cats.effect.*
import cats.mtl.Raise
import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.http4s.Http4sClient
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl, ElasticError, Index as ESIndex, Indexable }
import lila.search.ESClient.MetricKeys.*
import org.http4s.Uri
import org.http4s.client.Client
import org.typelevel.otel4s.metrics.{ Histogram, Meter }
import org.typelevel.otel4s.{ Attribute, AttributeKey, Attributes }

import java.util.concurrent.TimeUnit

trait ESClient[F[_]]:
  type RaiseF[A] = Raise[F, ElasticError] ?=> F[A]

  def search[A](query: A, from: From, size: Size)(using Queryable[A]): RaiseF[List[Id]]
  def count[A](query: A)(using Queryable[A]): RaiseF[Long]
  def store[A](index: Index, id: Id, obj: A)(using Indexable[A]): RaiseF[Unit]
  def storeBulk[A](index: Index, objs: Seq[SourceWithId[A]])(using Indexable[A]): RaiseF[Unit]
  def deleteOne(index: Index, id: Id): RaiseF[Unit]
  def deleteMany(index: Index, ids: List[Id]): RaiseF[Unit]
  def putMapping(index: Index): RaiseF[Unit]
  def refreshIndex(index: Index): RaiseF[Unit]
  def status: RaiseF[String]

object ESClient:

  def apply(uri: Uri)(client: Client[IO])(using Meter[IO]): IO[ESClient[IO]] =
    Meter[IO]
      .histogram[Double]("db.client.operation.duration")
      .withUnit("ms")
      .create
      .map(
        apply(
          ElasticClient(new Http4sClient(client, uri)),
          Attributes(Attribute("db.system", "elasticsearch"), Attribute("server.address", uri.toString()))
        )
      )

  def apply[F[_]: MonadCancelThrow](client: ElasticClient[F], baseAttributes: Attributes)(
      metric: Histogram[F, Double]
  ) = new ESClient[F]:

    def status: RaiseF[String] =
      client
        .execute(clusterHealth())
        .flatMap(_.toResult)
        .map(_.status)

    def search[A](query: A, from: From, size: Size)(using Queryable[A]): RaiseF[List[Id]] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(dbOperationName, "search")
              .added(dbCollectionName, query.index.value)
          )
        )
        .surround:
          client
            .execute(query.searchDef(from, size))
            .flatMap(_.toResult)
            .map(_.hits.hits.toList.map(h => Id(h.id)))

    def count[A](query: A)(using Queryable[A]): RaiseF[Long] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(dbOperationName, "count")
              .added(dbCollectionName, query.index.value)
          )
        )
        .surround:
          client
            .execute(query.countDef)
            .flatMap(_.toResult)
            .map(_.count)

    def store[A](index: Index, id: Id, obj: A)(using Indexable[A]): RaiseF[Unit] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(dbOperationName, "store")
              .added(dbCollectionName, index.value)
          )
        )
        .surround:
          client
            .execute(indexInto(index.value).source(obj).id(id.value))
            .flatMap(_.unitOrFail)

    def storeBulk[A](index: Index, objs: Seq[SourceWithId[A]])(using Indexable[A]): RaiseF[Unit] =
      val request = indexInto(index.value)
      val requests = bulk(objs.map { case (id, source) => request.source(source).id(id) })
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(dbOperationName, "store-bulk")
              .added(dbCollectionName, index.value)
              .added(dbBatchSize, objs.size)
          )
        )
        .surround:
          client
            .execute(requests)
            .flatMap(_.unitOrFail)
        .whenA(objs.nonEmpty)

    def deleteOne(index: Index, id: Id): RaiseF[Unit] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(dbOperationName, "delete-one")
              .added(dbCollectionName, index.value)
          )
        )
        .surround:
          client
            .execute(deleteById(index.toES, id.value))
            .flatMap(_.unitOrFail)

    def deleteMany(index: Index, ids: List[Id]): RaiseF[Unit] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(dbOperationName, "delete-bulk")
              .added(dbCollectionName, index.value)
              .added(dbBatchSize, ids.size)
          )
        )
        .surround:
          client
            .execute(bulk(ids.map(id => deleteById(index.toES, id.value))))
            .flatMap(_.unitOrFail)
        .whenA(ids.nonEmpty)

    def putMapping(index: Index): RaiseF[Unit] =
      dropIndex(index) *> client
        .execute:
          createIndex(index.value)
            .mapping(properties(index.mapping).source(false)) // all false
            .shards(5)
            .replicas(0)
            .refreshInterval(index.refreshInterval)
        .flatMap(_.unitOrFail)

    def refreshIndex(index: Index): RaiseF[Unit] =
      client
        .execute(ElasticDsl.refreshIndex(index.value))
        .flatMap(_.unitOrFail)

    private def dropIndex(index: Index) =
      client.execute(deleteIndex(index.value))

  object MetricKeys:
    val dbCollectionName = AttributeKey.string("db.collection.name")
    val dbBatchSize = AttributeKey.long("db.operation.batch.size")
    val dbOperationName = AttributeKey.string("db.operation.name")
    val errorType = AttributeKey.string("error.type")

  private def withErrorType(static: Attributes)(ec: Resource.ExitCase): Attributes = ec match
    case Resource.ExitCase.Succeeded =>
      static
    case Resource.ExitCase.Errored(e) =>
      static.added(errorType, e.getClass.getName)
    case Resource.ExitCase.Canceled =>
      static.added(errorType, "canceled")
