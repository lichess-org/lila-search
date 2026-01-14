package lila.search
package test

import akka.actor.ActorSystem
import cats.effect.{ IO, Resource }
import com.comcast.ip4s.*
import com.sksamuel.elastic4s.Indexable
import lila.search.app.{ App, AppConfig, AppResources, ElasticConfig, HttpServerConfig }
import lila.search.client.{ SearchClient, SearchError }
import lila.search.spec.{ CountOutput, Query, SearchOutput }
import org.http4s.implicits.*
import org.typelevel.log4cats.noop.{ NoOpFactory, NoOpLogger }
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.exporter.prometheus.PrometheusMetricExporter
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter
import play.api.libs.ws.ahc.*

import scala.concurrent.ExecutionContext.Implicits.*

object CompatSuite extends weaver.IOSuite:

  given Logger[IO] = NoOpLogger[IO]
  given LoggerFactory[IO] = NoOpFactory[IO]
  given MeterProvider[IO] = MeterProvider.noop[IO]

  override type Res = SearchClient

  override def sharedResource: Resource[IO, Res] =
    for
      given MetricExporter.Pull[IO] <- PrometheusMetricExporter.builder[IO].build.toResource
      _ <- App.mkServer(AppResources(fakeESClient), testAppConfig)
      wsClient <- makeWSClient
    yield SearchClient.play(wsClient, "http://localhost:9999/api")

  val from = From(0)
  val size = Size(12)

  test("search endpoint"): client =>
    val query = Query.Forum("foo")
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("bad search study endpoint"): client =>
    val query = Query.Study(
      text =
        "哈尔滨双城区《哪个酒店有小姐服务汽车站》【威信：█184-0823-1261█ 提供上门服务】面到付款  有工作室，精挑细选，各种类型，应有尽有，诚信经营，坚决不做一次性买卖！国内一二线城市均可安排💯6sFW"
          .take(100),
      userId = Some(value = "bla")
    )
    IO.fromFuture(IO(client.search(query, from, size)))
      .handleErrorWith:
        case e: SearchError.JsonWriterError =>
          IO(SearchOutput(Nil))
      .map(expect.same(_, SearchOutput(Nil)))

  test("count endpoint"): client =>
    val query = Query.Team("foo")
    IO.fromFuture(IO(client.count(query))).map(expect.same(_, lila.search.spec.CountOutput(0)))

  test("search study by chapter name"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      chapterName = Some("opening trap")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("search study by ECO code"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      eco = Some("B90")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("search study by player white"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      playerWhite = Some("Magnus Carlsen")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("search study by opening name"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      opening = Some("King's Indian")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("search study with combined filters"): client =>
    val query = Query.Study(
      text = "repertoire",
      userId = None,
      sorting = None,
      eco = Some("E97"),
      opening = Some("King's Indian")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("count study with chapter filters"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      chapterName = Some("sicilian")
    )
    IO.fromFuture(IO(client.count(query))).map(expect.same(_, lila.search.spec.CountOutput(0)))

  test("search study by variant"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      variant = Some("standard")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("search study by event"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      event = Some("World Championship")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  test("search study by FIDE IDs"): client =>
    val query = Query.Study(
      text = "",
      userId = None,
      sorting = None,
      whiteFideId = Some("1503014"),
      blackFideId = Some("2020009")
    )
    IO.fromFuture(IO(client.search(query, from, size))).map(expect.same(_, SearchOutput(Nil)))

  def testAppConfig = AppConfig(
    server = HttpServerConfig(ip"0.0.0.0", port"9999", false, shutdownTimeout = 1, false),
    elastic = ElasticConfig(uri"http://0.0.0.0:9200")
  )

  def fakeESClient: ESClient[IO] = new:

    override def store[A](index: Index, id: Id, obj: A)(using Indexable[A]) = IO.unit

    override def storeBulk[A](index: Index, objs: Seq[A])(using Indexable[A], HasStringId[A]) =
      IO.unit

    override def update[A](index: Index, id: Id, map: Map[String, Any]): RaiseF[Unit] = IO.unit

    override def updateBulk[A](index: Index, docs: List[(Id, Map[String, Any])]): RaiseF[Unit] = IO.unit

    override def putMapping(index: Index) = IO.unit

    override def refreshIndex(index: Index) = IO.unit

    override def deleteOne(index: Index, id: Id) = IO.unit

    override def deleteMany(index: Index, ids: List[Id]) = IO.unit

    override def count[A](query: A)(using Queryable[A]) =
      IO.pure(0)

    override def search[A](query: A, from: From, size: Size)(using Queryable[A]) =
      IO.pure(Nil)

    override def status = IO("yellow")

    override def indexExists(index: Index) = IO(true)

  given system: ActorSystem = ActorSystem()

  private def makeWSClient = Resource.make(IO(StandaloneAhcWSClient()))(x =>
    IO(x.close()).flatMap(_ => IO.fromFuture(IO(system.terminate())).void)
  )
