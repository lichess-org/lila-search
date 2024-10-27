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
import org.typelevel.otel4s.metrics.{ Counter, Histogram, Meter }
import org.typelevel.otel4s.{ Attribute, AttributeKey }

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
        (
          meter.counter[Long]("count.error").create,
          meter.histogram[Double]("count.duration").withUnit("ms").create,
          meter.counter[Long]("search.error").create,
          meter.histogram[Double]("search.duration").withUnit("ms").create,
          meter.counter[Long]("store.one.error").create,
          meter.histogram[Double]("store.one.duration").withUnit("ms").create,
          meter.counter[Long]("store.bulk.error").create,
          meter.histogram[Double]("store.bulk.duration").withUnit("ms").create,
          meter.counter[Long]("delete.one.error").create,
          meter.histogram[Double]("delete.one.duration").withUnit("ms").create,
          meter.counter[Long]("delete.many.error").create,
          meter.histogram[Double]("delete.many.duration").withUnit("ms").create
        ).mapN(apply(esClient))

  def apply[F[_]: MonadCancelThrow: Functor: Executor](client: ElasticClient)(
      countErrorCounter: Counter[F, Long],
      countDuration: Histogram[F, Double],
      searchErrorCounter: Counter[F, Long],
      searchDuration: Histogram[F, Double],
      storeErrorCounter: Counter[F, Long],
      storeDuration: Histogram[F, Double],
      storeBulkErrorCounter: Counter[F, Long],
      storeBulkDuration: Histogram[F, Double],
      deleteOneErrorCounter: Counter[F, Long],
      deleteOneDuration: Histogram[F, Double],
      deleteManyErrorCounter: Counter[F, Long],
      deleteManyDuration: Histogram[F, Double]
  ) = new ESClient[F]:

    def status: F[String] =
      client
        .execute(clusterHealth())
        .flatMap(toResult)
        .map(_.status)

    private def toResult[A](response: Response[A]): F[A] =
      response.fold(MonadThrow[F].raiseError[A](response.error.asException))(MonadThrow[F].pure)

    private def unitOrFail[A](response: Response[A]): F[Unit] =
      response.fold(MonadThrow[F].raiseError[Unit](response.error.asException))(_ => MonadThrow[F].unit)

    val indexAttributeKey = AttributeKey.string("index")
    val sizeAttributeKey  = AttributeKey.long("size")

    def search[A](query: A, from: From, size: Size)(using q: Queryable[A]): F[List[Id]] =
      searchDuration
        .recordDuration(TimeUnit.MILLISECONDS, Attribute(indexAttributeKey, q.index(query).value))
        .surround:
          client
            .execute(q.searchDef(query)(from, size))
            .flatMap(toResult)
            .map(_.hits.hits.toList.map(h => Id(h.id)))
            .onError(_ => searchErrorCounter.inc(Attribute(indexAttributeKey, q.index(query).value)))

    def count[A](query: A)(using q: Queryable[A]): F[Long] =
      countDuration
        .recordDuration(TimeUnit.MILLISECONDS, Attribute(indexAttributeKey, q.index(query).value))
        .surround:
          client
            .execute(q.countDef(query))
            .flatMap(toResult)
            .map(_.count)
            .onError(_ => countErrorCounter.inc(Attribute(indexAttributeKey, q.index(query).value)))

    def store[A](index: Index, id: Id, obj: A)(using indexable: Indexable[A]): F[Unit] =
      storeDuration
        .recordDuration(TimeUnit.MILLISECONDS, Attribute(indexAttributeKey, index.value))
        .surround:
          client
            .execute(indexInto(index.value).source(obj).id(id.value))
            .flatMap(unitOrFail)
            .onError(_ => storeErrorCounter.inc(Attribute(indexAttributeKey, index.value)))

    def storeBulk[A](index: Index, objs: Seq[(String, A)])(using indexable: Indexable[A]): F[Unit] =
      val request  = indexInto(index.value)
      val requests = bulk(objs.map((id, obj) => request.source(obj).id(id)))
      storeBulkDuration
        .recordDuration(
          TimeUnit.MILLISECONDS,
          Attribute(indexAttributeKey, index.value),
          Attribute(sizeAttributeKey, objs.size)
        )
        .surround:
          client
            .execute(requests)
            .flatMap(unitOrFail)
            .onError: _ =>
              storeBulkErrorCounter
                .inc(Attribute(indexAttributeKey, index.value), Attribute(sizeAttributeKey, objs.size))
        .whenA(objs.nonEmpty)

    def deleteOne(index: Index, id: Id): F[Unit] =
      deleteOneDuration
        .recordDuration(TimeUnit.MILLISECONDS, Attribute(indexAttributeKey, index.value))
        .surround:
          client
            .execute(deleteById(index.toES, id.value))
            .flatMap(unitOrFail)
            .onError(_ => deleteOneErrorCounter.inc(Attribute(indexAttributeKey, index.value)))

    def deleteMany(index: Index, ids: List[Id]): F[Unit] =
      deleteManyDuration
        .recordDuration(
          TimeUnit.MILLISECONDS,
          Attribute(indexAttributeKey, index.value),
          Attribute(sizeAttributeKey, ids.size)
        )
        .surround:
          client
            .execute(bulk(ids.map(id => deleteById(index.toES, id.value))))
            .flatMap(unitOrFail)
            .onError: _ =>
              deleteOneErrorCounter
                .inc(Attribute(indexAttributeKey, index.value), Attribute(sizeAttributeKey, ids.size))
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
