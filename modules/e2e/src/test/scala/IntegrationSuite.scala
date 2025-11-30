package lila.search
package app
package test

import cats.Functor
import cats.effect.{ IO, Resource }
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.sksamuel.elastic4s.ElasticError
import lila.search.es.*
import lila.search.ingestor.IndexRegistry.given
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

object IntegrationSuite extends IOSuite:

  given Logger[IO] = NoOpLogger[IO]
  given LoggerFactory[IO] = NoOpFactory[IO]
  given MeterProvider[IO] = MeterProvider.noop[IO]
  private given Raise[IO, ElasticError]:
    def functor: Functor[IO] = Functor[IO]
    def raise[E <: ElasticError, A](e: E): IO[A] =
      IO.raiseError(e.asException)

  private val uri = Uri.unsafeFromString("http://localhost:9999")

  val from = From(0)
  val size = Size(12)
  override type Res = AppResources
  // start our server
  override def sharedResource: Resource[IO, Res] =
    for
      elastic <- ElasticSearchContainer.start
      config = testAppConfig(elastic)
      res <- AppResources.instance(config)
      given MetricExporter.Pull[IO] <- PrometheusMetricExporter.builder[IO].build.toResource
      _ <- App.mkServer(res, config)
    yield res

  def testAppConfig(elastic: ElasticConfig) = AppConfig(
    server =
      HttpServerConfig(ip"0.0.0.0", port"9999", apiLogger = false, shutdownTimeout = 1, enableDocs = false),
    elastic = elastic
  )

  test("health check should return healthy"):
    Clients
      .health(uri)
      .use:
        _.healthCheck()
          .map(expect.same(_, HealthCheckOutput(ElasticStatus.green)))

  test("forum"): res =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- res.esClient.putMapping(Index.Forum)
          _ <- res.esClient.store(
            Index.Forum,
            Id("forum_id"),
            ForumSource(
              body = "a forum post",
              topic = "chess",
              topicId = "chess",
              troll = false,
              date = Instant.now().toEpochMilli(),
              author = "nt9".some
            )
          )
          _ <- res.esClient.refreshIndex(Index.Forum)
          x <- service.search(Query.forum("chess", false), from, size)
          y <- service.search(Query.forum("nt9", false), from, size)
        yield expect(x.hitIds.size == 1 && x == y)

  test("ublog"): res =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- res.esClient.putMapping(Index.Ublog)
          _ <- res.esClient.store(
            Index.Ublog,
            Id("abcdefgh"),
            UblogSource(
              text = "lil bubber, hayo!",
              language = "en",
              likes = 0,
              date = Instant.now().toEpochMilli(),
              quality = 1.some
            )
          )
          _ <- res.esClient.refreshIndex(Index.Ublog)
          x <- service.search(Query.ublog("lil bubber", SortBlogsBy.score, 1.some), from, size)
          y <- service.search(Query.ublog("hayo", SortBlogsBy.newest, 2.some), from, size)
        yield expect(x.hitIds.size == 1 && y.hitIds.isEmpty)

  test("team"): res =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- res.esClient.putMapping(Index.Team)
          _ <- res.esClient.store(
            Index.Team,
            Id("team_id"),
            TeamSource(
              name = "team name",
              description = "team description",
              100
            )
          )
          _ <- res.esClient.refreshIndex(Index.Team)
          x <- service.search(Query.team("team name"), from, size)
          y <- service.search(Query.team("team description"), from, size)
        yield expect(x.hitIds.size == 1 && x == y)

  test("study"): res =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- res.esClient.putMapping(Index.Study)
          _ <- res.esClient.store(
            Index.Study,
            Id("study_id"),
            StudySource(
              name = "study name",
              owner = "study owner",
              members = List("member1", "member2"),
              chapterNames = "chapter one",
              chapterTexts = "study description",
              likes = 100,
              public = true,
              topics = List("topic1", "topic2")
            )
          )
          _ <- res.esClient.refreshIndex(Index.Study)
          a <- service.search(Query.study("name"), from, size)
          b <- service.search(Query.study("study description"), from, size)
          c <- service.search(Query.study("topic1"), from, size)
        yield expect(a.hitIds.size == 1 && b == a && c == a)

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
  test("game"): res =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- res.esClient.putMapping(Index.Game)
          _ <- res.esClient.store(
            Index.Game,
            Id("game_id"),
            GameSource(
              status = 1,
              turns = 100,
              rated = true,
              perf = 1,
              winnerColor = 1,
              date = SearchDateTime.fromInstant(Timestamp(1999, 10, 20, 12, 20, 20).toInstant),
              analysed = false,
              uids = List("uid1", "uid2").some,
              winner = "uid1".some,
              loser = "uid2".some,
              averageRating = 150.some,
              ai = none,
              duration = 100.some,
              clockInit = 100.some,
              clockInc = 200.some,
              whiteUser = "white".some,
              blackUser = "black".some
            )
          )
          _ <- res.esClient.refreshIndex(Index.Game)
          a <- service.search(defaultGame.copy(perf = List(1)), from, size)
          b <- service.search(defaultGame.copy(loser = "uid2".some), from, size)
          c <- service.search(defaultGame, from, size)
          d <- service.search(defaultGame.copy(duration = IntRange(a = 99.some, b = 101.some)), from, size)
          e <- service.search(defaultGame.copy(clockInit = 100.some), from, size)
          f <- service.search(defaultGame.copy(clockInc = 200.some), from, size)
          g <- service.search(
            defaultGame.copy(date = DateRange(a = none, b = Timestamp(1999, 10, 20, 12, 20, 21).some)),
            from,
            size
          )
        yield expect(a.hitIds.size == 1 && b == a && c == a && d == a && e == a && f == a && g == a)
