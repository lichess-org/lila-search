package lila.search
package test

import akka.actor.ActorSystem
import cats.effect.{ IO, Resource }
import com.comcast.ip4s.*
import com.sksamuel.elastic4s.Indexable
import com.sksamuel.elastic4s.fields.ElasticField
import lila.search.app.{ AppConfig, AppResources, ElasticConfig, HttpServerConfig, SearchApp }
import lila.search.client.{ SearchClient, SearchError }
import lila.search.spec.{ CountOutput, Index as SpecIndex, Query, SearchOutput, Source }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import play.api.libs.ws.*
import play.api.libs.ws.ahc.*

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.*

object CompatSuite extends weaver.IOSuite:

  given Logger[IO] = NoOpLogger[IO]

  override type Res = SearchClient

  override def sharedResource: Resource[IO, Res] =
    val res = AppResources(fakeClient)
    SearchApp(res, testAppConfig)
      .run()
      .flatMap(_ => wsClient)
      .map(SearchClient.play(_, "http://localhost:9999/api"))

  test("search endpoint"): client =>
    val query = Query.Forum("foo")
    IO.fromFuture(IO(client.search(query, 0, 10))).map(expect.same(_, SearchOutput(Nil)))

  test("bad search study endpoint"): client =>
    val query = Query.Study(
      text =
        "哈尔滨双城区《哪个酒店有小姐服务汽车站》【威信：█184-0823-1261█ 提供上门服务】面到付款  有工作室，精挑细选，各种类型，应有尽有，诚信经营，坚决不做一次性买卖！国内一二线城市均可安排💯6sFW"
          .take(100),
      userId = Some(value = "bla")
    )
    IO.fromFuture(IO(client.search(query, 0, 10)))
      .handleErrorWith:
        case e: SearchError.JsonWriterError =>
          IO.pure(SearchOutput(Nil))
      .map(expect.same(_, SearchOutput(Nil)))

  test("count endpoint"): client =>
    val query = Query.Team("foo")
    IO.fromFuture(IO(client.count(query))).map(expect.same(_, lila.search.spec.CountOutput(0)))

  test("deleteById endpoint"): client =>
    IO.fromFuture(IO(client.deleteById(SpecIndex.Game, "iddddd"))).map(expect.same(_, ()))

  test("deleteByIds endpoint"): client =>
    IO.fromFuture(IO(client.deleteByIds(SpecIndex.Game, List("a", "b", "c")))).map(expect.same(_, ()))

  test("mapping endpoint"): client =>
    IO.fromFuture(IO(client.mapping(SpecIndex.Study))).map(expect.same(_, ()))

  test("refresh endpoint"): client =>
    IO.fromFuture(IO(client.refresh(SpecIndex.Forum))).map(expect.same(_, ()))

  test("store endpoint"): client =>
    val source = Source.team(lila.search.spec.TeamSource("names", "desc", 100))
    IO.fromFuture(IO(client.store("id", source))).map(expect.same(_, ()))

  test("store bulk forum endpoint"): client =>
    val now = Instant.now().toEpochMilli()
    val sources = List(
      lila.search.spec.ForumSourceWithId(
        "id1",
        lila.search.spec
          .ForumSource("body1", "topic1", "topid1", true, now)
      ),
      lila.search.spec.ForumSourceWithId(
        "id2",
        lila.search.spec
          .ForumSource("body2", "topic2", "topid2", true, now)
      )
    )
    IO.fromFuture(IO(client.storeBulkForum(sources))).map(expect.same(_, ()))

  test("store bulk game endpoint"): client =>
    val now = SearchDateTime.fromInstant(Instant.now())
    val sources = List(
      lila.search.spec.GameSourceWithId(
        "id1",
        lila.search.spec
          .GameSource(1, 1, true, 4, 1, now, true)
      ),
      lila.search.spec.GameSourceWithId(
        "id2",
        lila.search.spec
          .GameSource(2, 2, true, 4, 1, now, false)
      )
    )
    IO.fromFuture(IO(client.storeBulkGame(sources))).map(expect.same(_, ()))

  test("store bulk study endpoint"): client =>
    val sources = List(
      lila.search.spec.StudySourceWithId(
        "id1",
        lila.search.spec.StudySource("name1", "owner1", Nil, "chapter names", "chapter texts", 12, true)
      ),
      lila.search.spec.StudySourceWithId(
        "id2",
        lila.search.spec.StudySource("name2", "owner2", Nil, "chapter names", "chapter texts", 12, false)
      )
    )
    IO.fromFuture(IO(client.storeBulkStudy(sources))).map(expect.same(_, ()))

  test("store bulk team endpoint"): client =>
    val sources = List(
      lila.search.spec.TeamSourceWithId(
        "id1",
        lila.search.spec.TeamSource("names1", "desc1", 100)
      ),
      lila.search.spec.TeamSourceWithId(
        "id2",
        lila.search.spec.TeamSource("names2", "desc2", 200)
      )
    )
    IO.fromFuture(IO(client.storeBulkTeam(sources))).map(expect.same(_, ()))

  def testAppConfig = AppConfig(
    server = HttpServerConfig(ip"0.0.0.0", port"9999", false, shutdownTimeout = 1, false),
    elastic = ElasticConfig("http://0.0.0.0:9200")
  )

  def fakeClient: ESClient[IO] = new ESClient[IO]:

    override def store[A](index: Index, id: Id, obj: A)(implicit
        indexable: Indexable[A]
    ): IO[Unit] = IO.unit

    override def storeBulk[A](index: Index, objs: Seq[(String, A)])(implicit
        indexable: Indexable[A]
    ): IO[Unit] = IO.unit

    override def putMapping(index: Index, fields: Seq[ElasticField]): IO[Unit] = IO.unit

    override def refreshIndex(index: Index): IO[Unit] = IO.unit

    override def deleteOne(index: Index, id: Id): IO[Unit] = IO.unit

    override def deleteMany(index: Index, ids: List[Id]): IO[Unit] = IO.unit

    override def count[A](query: A)(implicit q: Queryable[A]): IO[CountResponse] =
      IO.pure(CountResponse(0))

    override def search[A](query: A, from: From, size: Size)(implicit q: Queryable[A]): IO[SearchResponse] =
      IO.pure(SearchResponse(Nil))

    override def status: IO[String] = IO.pure("yellow")

  given system: ActorSystem = ActorSystem()

  def wsClient = Resource.make(IO(StandaloneAhcWSClient()))(x =>
    IO(x.close()).flatMap(_ => IO.fromFuture(IO(system.terminate())).void)
  )
