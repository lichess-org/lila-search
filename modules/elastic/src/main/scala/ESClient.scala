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

  def apply(uri: String)(using meter: Meter[IO]): Resource[IO, ESClient[IO]] =
    Resource
      .make(IO(ElasticClient(JavaClient(ElasticProperties(uri)))))(client => IO(client.close()))
      .evalMap: esClient =>
        meter
          .histogram[Double]("client.duration")
          .withUnit("ms")
          .create
          .map(apply(esClient))

  def apply[F[_]: MonadCancelThrow: Functor: Executor](client: ElasticClient)(
      metric: Histogram[F, Double]
  ) = new ESClient[F]:

    def status: F[String] =
      client
        .execute(clusterHealth())
        .flatMap(toResult)
        .map(_.status)

    private def withErrorType(static: Attributes)(ec: Resource.ExitCase) = ec match
      case Resource.ExitCase.Succeeded =>
        static
      case Resource.ExitCase.Errored(e) =>
        static.added(Attribute("error.type", e.getClass.getName))
      case Resource.ExitCase.Canceled =>
        static.added(Attribute("error.type", "canceled"))

    private def toResult[A](response: Response[A]): F[A] =
      response.fold(MonadThrow[F].raiseError[A](response.error.asException))(MonadThrow[F].pure)

    private def unitOrFail[A](response: Response[A]): F[Unit] =
      response.fold(MonadThrow[F].raiseError[Unit](response.error.asException))(_ => MonadThrow[F].unit)

    val indexAttributeKey = AttributeKey.string("index")
    val sizeAttributeKey  = AttributeKey.long("size")
    val opAttributeKey    = AttributeKey.string("name")

    def search[A](query: A, from: From, size: Size)(using q: Queryable[A]): F[List[Id]] =
      metric
        .recordDuration(
          TimeUnit.MILLISECONDS,
          withErrorType(
            Attributes(
              Attribute(opAttributeKey, "search"),
              Attribute(indexAttributeKey, q.index(query).value)
            )
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
            Attributes(
              Attribute(opAttributeKey, "count"),
              Attribute(indexAttributeKey, q.index(query).value)
            )
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
            Attributes(
              Attribute(opAttributeKey, "store-one"),
              Attribute(indexAttributeKey, index.value)
            )
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
            Attributes(
              Attribute(opAttributeKey, "store-bulk"),
              Attribute(indexAttributeKey, index.value),
              Attribute(sizeAttributeKey, objs.size)
            )
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
            Attributes(
              Attribute(opAttributeKey, "delete-one"),
              Attribute(indexAttributeKey, index.value)
            )
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
            Attributes(
              Attribute(opAttributeKey, "delete-bulk"),
              Attribute(indexAttributeKey, index.value),
              Attribute(sizeAttributeKey, ids.size)
            )
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
