package lila.search

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
import org.typelevel.otel4s.metrics.{ Histogram, Meter }
import org.typelevel.otel4s.{ Attribute, AttributeKey, Attributes }

import java.util.concurrent.TimeUnit

trait ESClient[F[_]]:

  def search[A](query: A, from: From, size: Size)(using Queryable[A]): F[List[Id]]
  def count[A](query: A)(using Queryable[A]): F[Long]
  def store[A](index: Index, id: Id, obj: A)(using Indexable[A]): F[Unit]
  def storeBulk[A](index: Index, objs: Seq[(String, A)])(using Indexable[A]): F[Unit]
  def deleteOne(index: Index, id: Id): F[Unit]
  def deleteMany(index: Index, ids: List[Id]): F[Unit]
  def putMapping(index: Index): F[Unit]
  def refreshIndex(index: Index): F[Unit]
  def status: F[String]

object ESClient:

  object MetricKeys:
    val dbCollectionName = AttributeKey.string("db.collection.name")
    val dbBatchSize      = AttributeKey.long("db.operation.batch.size")
    val dbOperationName  = AttributeKey.string("db.operation.name")
    val errorType        = AttributeKey.string("error.type")

  import lila.search.ESClient.MetricKeys.*
  private def withErrorType(static: Attributes)(ec: Resource.ExitCase): Attributes = ec match
    case Resource.ExitCase.Succeeded =>
      static
    case Resource.ExitCase.Errored(e) =>
      static.added(Attribute(errorType, e.getClass.getName))
    case Resource.ExitCase.Canceled =>
      static.added(Attribute(errorType, "canceled"))

  def apply(uri: String)(using meter: Meter[IO]): Resource[IO, ESClient[IO]] =
    Resource
      .make(IO(ElasticClient(JavaClient(ElasticProperties(uri)))))(client => IO(client.close()))
      .evalMap: esClient =>
        meter
          .histogram[Double]("db.client.operation.duration")
          .withUnit("ms")
          .create
          .map(
            apply(
              esClient,
              Attributes(Attribute("db.system", "elasticsearch"), Attribute("server.address", uri))
            )
          )

  def apply[F[_]: MonadCancelThrow: Functor: Executor](client: ElasticClient, baseAttributes: Attributes)(
      metric: Histogram[F, Double]
  ) = new ESClient[F]:

    def status: F[String] =
      client
        .execute(clusterHealth())
        .flatMap(toResult)
        .map(_.status)

    private def toResult[A](response: Response[A]): F[A] =
      response.fold(response.error.asException.raiseError)(r => r.pure[F])

    private def unitOrFail[A](response: Response[A]): F[Unit] =
      response.fold(response.error.asException.raiseError)(_ => ().pure[F])

    def search[A](query: A, from: From, size: Size)(using q: Queryable[A]): F[List[Id]] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(Attribute(dbOperationName, "search"))
              .added(Attribute(dbCollectionName, q.index(query).value))
          )
        )
        .surround:
          client
            .execute(q.searchDef(query)(from, size))
            .flatMap(toResult)
            .map(_.hits.hits.toList.map(h => Id(h.id)))

    def count[A](query: A)(using q: Queryable[A]): F[Long] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(Attribute(dbOperationName, "count"))
              .added(Attribute(dbCollectionName, q.index(query).value))
          )
        )
        .surround:
          client
            .execute(q.countDef(query))
            .flatMap(toResult)
            .map(_.count)

    def store[A](index: Index, id: Id, obj: A)(using indexable: Indexable[A]): F[Unit] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(Attribute(dbOperationName, "store"))
              .added(Attribute(dbCollectionName, index.value))
          )
        )
        .surround:
          client
            .execute(indexInto(index.value).source(obj).id(id.value))
            .flatMap(unitOrFail)

    def storeBulk[A](index: Index, objs: Seq[(String, A)])(using indexable: Indexable[A]): F[Unit] =
      val request  = indexInto(index.value)
      val requests = bulk(objs.map((id, obj) => request.source(obj).id(id)))
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(Attribute(dbOperationName, "store-bulk"))
              .added(Attribute(dbCollectionName, index.value))
              .added(Attribute(dbBatchSize, objs.size))
          )
        )
        .surround:
          client
            .execute(requests)
            .flatMap(unitOrFail)
        .whenA(objs.nonEmpty)

    def deleteOne(index: Index, id: Id): F[Unit] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(Attribute(dbOperationName, "delete-one"))
              .added(Attribute(dbCollectionName, index.value))
          )
        )
        .surround:
          client
            .execute(deleteById(index.toES, id.value))
            .flatMap(unitOrFail)

    def deleteMany(index: Index, ids: List[Id]): F[Unit] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            baseAttributes
              .added(Attribute(dbOperationName, "delete-bulk"))
              .added(Attribute(dbCollectionName, index.value))
              .added(Attribute(dbBatchSize, ids.size))
          )
        )
        .surround:
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
            .refreshInterval(index.refreshInterval)
        .flatMap(unitOrFail)

    def refreshIndex(index: Index): F[Unit] =
      client
        .execute(ElasticDsl.refreshIndex(index.value))
        .flatMap(unitOrFail)

    private def dropIndex(index: Index) =
      client.execute(deleteIndex(index.value))
