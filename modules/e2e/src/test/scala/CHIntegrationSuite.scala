package lila.search
package app
package test

import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import com.comcast.ip4s.*
import lila.search.clickhouse.ClickHouseClient
import lila.search.clickhouse.game.{ GameRow, WinnerColor }
import lila.search.spec.*
import org.http4s.Uri
import org.typelevel.log4cats.noop.{ NoOpFactory, NoOpLogger }
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.exporter.prometheus.PrometheusMetricExporter
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter
import smithy4s.Timestamp
import weaver.*

import java.time.Instant

object CHIntegrationSuite extends IOSuite:

  given Logger[IO] = NoOpLogger[IO]
  given LoggerFactory[IO] = NoOpFactory[IO]
  given MeterProvider[IO] = MeterProvider.noop[IO]

  private val uri = Uri.unsafeFromString("http://localhost:9998")

  val from = From(0)
  val size = Size(12)
  override type Res = ClickHouseClient[IO]

  override def sharedResource: Resource[IO, Res] =
    for
      elastic <- ElasticSearchContainer.start
      (chConfig, chClient) <- ClickHouseContainer.start
      config = AppConfig(
        server = HttpServerConfig(
          ip"0.0.0.0",
          port"9998",
          apiLogger = false,
          shutdownTimeout = 1,
          enableDocs = false
        ),
        elastic = elastic,
        clickhouse = chConfig,
        gameBackend = GameSearchBackend.ClickHouseOnly
      )
      res <- AppResources.instance(config)
      given MetricExporter.Pull[IO] <- PrometheusMetricExporter.builder[IO].build.toResource
      _ <- App.mkServer(res, config)
    yield chClient

  val defaultIntRange = IntRange(none, none)
  val defaultDateRange = DateRange(none, none)
  val defaultGame = Query.game(
    turns = defaultIntRange,
    averageRating = defaultIntRange,
    aiLevel = defaultIntRange,
    date = defaultDateRange,
    duration = defaultIntRange,
    sorting = GameSorting("field", "asc")
  )

  private def fixture(
      id: String,
      players: List[String],
      turns: Int = 40,
      rated: Boolean = false,
      perf: Int = 1,
      aiLevel: Int = 0,
      date: Instant = Instant.now(),
      status: Int = 30,
      whiteRating: Int = 0,
      blackRating: Int = 0,
      winnerColor: WinnerColor = WinnerColor.Unknown,
      duration: Int = 0,
      clockInit: Int = -1,
      clockInc: Int = -1,
      source: Int = 0
  ): GameRow =
    GameRow(
      id = id,
      status = status,
      turns = turns,
      rated = rated,
      perf = perf,
      winnerColor = winnerColor,
      date = date,
      analysed = false,
      whiteRating = whiteRating,
      blackRating = blackRating,
      aiLevel = aiLevel,
      duration = duration,
      clockInit = clockInit,
      clockInc = clockInc,
      whiteUser = players.headOption.getOrElse(""),
      blackUser = players.lift(1).getOrElse(""),
      source = source,
      chess960Position = 1000,
      whiteBot = false,
      blackBot = false
    )

  test("game search via CH"): ch =>
    val user = "e2e_main_white"
    val scoped = defaultGame.copy(user1 = user.some)
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- ch.upsertGameRows(
            List(
              fixture(
                id = "e2egame_",
                players = List(user, "e2e_main_black"),
                turns = 100,
                rated = true,
                perf = 1,
                winnerColor = WinnerColor.White,
                date = Timestamp(1999, 10, 20, 12, 20, 20).toInstant,
                whiteRating = 150,
                blackRating = 150,
                duration = 100,
                clockInit = 100,
                clockInc = 200
              )
            )
          )
          a <- service.search(scoped.copy(perf = List(1)), from, size)
          b <- service.search(scoped.copy(loser = "e2e_main_black".some), from, size)
          c <- service.search(scoped, from, size)
          d <- service.search(scoped.copy(duration = IntRange(a = 99.some, b = 101.some)), from, size)
          e <- service.search(scoped.copy(clockInit = 100.some), from, size)
          f <- service.search(scoped.copy(clockInc = 200.some), from, size)
          g <- service.search(
            scoped.copy(date = DateRange(a = none, b = Timestamp(1999, 10, 20, 12, 20, 21).some)),
            from,
            size
          )
        yield expect(a.hitIds.size == 1) and
          expect.same(b, a) and
          expect.same(c, a) and
          expect.same(d, a) and
          expect.same(e, a) and
          expect.same(f, a) and
          expect.same(g, a)

  test("game count via CH"): ch =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- ch.upsertGameRows(
            List(
              fixture(id = "e2ecnt_1", players = List("cnt_user1"), rated = true),
              fixture(id = "e2ecnt_2", players = List("cnt_user1"), rated = true),
              fixture(id = "e2ecnt_3", players = List("cnt_user1"), rated = false)
            )
          )
          count <- service.count(defaultGame.copy(user1 = "cnt_user1".some, rated = true.some))
        yield expect(count.count == 2)

  test("game search with perf filter via CH"): ch =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- ch.upsertGameRows(
            List(
              fixture(id = "e2eperf1", players = List("perf_user1"), perf = 1),
              fixture(id = "e2eperf2", players = List("perf_user1"), perf = 2)
            )
          )
          result <- service.search(
            defaultGame.copy(user1 = "perf_user1".some, perf = List(1)),
            from,
            size
          )
        yield expect(result.hitIds == List(Id("e2eperf1")))

  test("game search with winner filter via CH"): ch =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- ch.upsertGameRows(
            List(
              fixture(
                id = "e2ewin_1",
                players = List("w_alice", "w_bob"),
                winnerColor = WinnerColor.White
              ),
              fixture(
                id = "e2ewin_2",
                players = List("w_alice", "w_bob"),
                winnerColor = WinnerColor.Black
              )
            )
          )
          wonByAlice <- service.search(defaultGame.copy(winner = "w_alice".some), from, size)
          wonByBob <- service.search(defaultGame.copy(winner = "w_bob".some), from, size)
        yield expect(wonByAlice.hitIds == List(Id("e2ewin_1"))) and
          expect(wonByBob.hitIds == List(Id("e2ewin_2")))

  test("game search with sorting via CH"): ch =>
    val earlyDate = Instant.ofEpochSecond(1_000_000_000L)
    val lateDate = Instant.ofEpochSecond(1_000_001_000L)
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- ch.upsertGameRows(
            List(
              fixture(id = "e2esrt_1", players = List("sort_user1"), date = earlyDate),
              fixture(id = "e2esrt_2", players = List("sort_user1"), date = lateDate)
            )
          )
          desc <- service.search(
            defaultGame.copy(
              user1 = "sort_user1".some,
              sorting = GameSorting("d", "desc")
            ),
            from,
            size
          )
        yield expect(desc.hitIds == List(Id("e2esrt_2"), Id("e2esrt_1")))
