package lila.search
package test

import cats.effect.{ IO, Resource }
import com.sksamuel.elastic4s.fields.ElasticField
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import lila.search.app.AppResources
import lila.search.app.SearchApp
import lila.search.client.PlayClient
import lila.search.app.{ AppConfig, ElasticConfig, HttpServerConfig }
import com.comcast.ip4s.*
import akka.actor.ActorSystem
import play.api.libs.ws.*
import play.api.libs.ws.ahc.*
import lila.search.spec.{ Query, Index as SpecIndex }
import scala.concurrent.ExecutionContext.Implicits.*

object CompatSuite extends weaver.IOSuite:

  given Logger[IO] = NoOpLogger[IO]

  override type Res = PlayClient

  override def sharedResource: Resource[IO, Res] =
    val res = AppResources(fakeClient)
    SearchApp(res, testAppConfig)
      .run()
      .flatMap(_ => wsClient)
      .map(PlayClient(_, "http://localhost:9999"))

  test("search endpoint"): client =>
    val query = Query.Forum("foo")
    IO.fromFuture(IO(client.search(query, 0, 10))).map(expect.same(_, lila.search.spec.SearchResponse(Nil)))

  test("count endpoint"): client =>
    val query = Query.Team("foo")
    IO.fromFuture(IO(client.count(query))).map(expect.same(_, lila.search.spec.CountResponse(0)))

  test("deleteById endpoint"): client =>
    IO.fromFuture(IO(client.deleteById(SpecIndex.Game, "iddddd"))).map(expect.same(_, ()))

  test("deleteByIds endpoint"): client =>
    IO.fromFuture(IO(client.deleteByIds(SpecIndex.Game, List("a", "b", "c")))).map(expect.same(_, ()))

  test("mapping endpoint"): client =>
    IO.fromFuture(IO(client.mapping(SpecIndex.Study))).map(expect.same(_, ()))

  def testAppConfig = AppConfig(
    server = HttpServerConfig(ip"0.0.0.0", port"9999", shutdownTimeout = 1),
    elastic = ElasticConfig("http://0.0.0.0:9200")
  )

  def fakeClient: ESClient[IO] = new ESClient[IO]:

    override def putMapping(index: Index, fields: Seq[ElasticField]): IO[Unit] = IO.unit

    override def refreshIndex(index: Index): IO[Unit] = IO.unit

    override def storeBulk(index: Index, objs: List[(String, JsonObject)]): IO[Unit] = IO.unit

    override def store(index: Index, id: Id, obj: JsonObject): IO[Unit] = IO.unit

    override def deleteOne(index: Index, id: Id): IO[Unit] = IO.unit

    override def deleteMany(index: Index, ids: List[Id]): IO[Unit] = IO.unit

    override def count[A](index: Index, query: A)(implicit q: Queryable[A]): IO[CountResponse] =
      IO.pure(CountResponse(0))

    override def search[A](index: Index, query: A, from: From, size: Size)(implicit
        q: Queryable[A]
    ): IO[SearchResponse] = IO.pure(SearchResponse(Nil))

  given system: ActorSystem = ActorSystem()

  def wsClient = Resource.make(IO(StandaloneAhcWSClient()))(x =>
    IO(x.close()).flatMap(_ => IO.fromFuture(IO(system.terminate())).void)
  )