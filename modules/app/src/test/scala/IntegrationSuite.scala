package lila.search
package app
package test

import cats.effect.{ IO, Resource }
import cats.syntax.all.*
import com.comcast.ip4s.*
import lila.search.spec.*
import org.http4s.Uri
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import smithy4s.Timestamp
import weaver.*

import java.time.Instant

object IntegrationSuite extends IOSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val uri = Uri.unsafeFromString("http://localhost:9999")

  val from = SearchFrom(0)
  val size = SearchSize(12)
  override type Res = AppResources
  // start our server
  override def sharedResource: Resource[IO, Res] =
    for
      elastic <- ElasticSearchContainer.start
      config = testAppConfig(elastic)
      res <- AppResources.instance(config)
      _   <- SearchApp(res, config).run()
    yield res

  def testAppConfig(elastic: ElasticConfig) = AppConfig(
    server =
      HttpServerConfig(ip"0.0.0.0", port"9999", apiLogger = false, shutdownTimeout = 30, enableDocs = false),
    elastic = elastic
  )

  test("health check should return healthy"):
    Clients
      .health(uri)
      .use:
        _.healthCheck()
          .map(expect.same(_, HealthCheckOutput(ElasticStatus.green)))

  test("forum"): _ =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- service.mapping(Index.Forum)
          _ <- service
            .store(
              "forum_id",
              Source.forum(
                ForumSource(
                  body = "a forum post",
                  topic = "chess",
                  topicId = "chess",
                  troll = false,
                  date = Instant.now().toEpochMilli(),
                  author = "nt9".some
                )
              )
            )
          _ <- service.refresh(Index.Forum)
          x <- service.search(Query.forum("chess", false), from, size)
          y <- service.search(Query.forum("nt9", false), from, size)
        yield expect(x.hitIds.size == 1 && x == y)

  test("team"): _ =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- service.mapping(Index.Team)
          _ <- service
            .store(
              "team_id",
              Source.team(
                TeamSource(
                  name = "team name",
                  description = "team description",
                  100
                )
              )
            )
          _ <- service.refresh(Index.Team)
          x <- service.search(Query.team("team name"), from, size)
          y <- service.search(Query.team("team description"), from, size)
        yield expect(x.hitIds.size == 1 && x == y)

  test("study"): _ =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- service.mapping(Index.Study)
          _ <- service
            .store(
              "study_id",
              Source.study(
                StudySource(
                  name = "study name",
                  owner = "study owner",
                  members = List("member1", "member2"),
                  chapterNames = "names",
                  chapterTexts = "texts",
                  likes = 100,
                  public = true,
                  topics = List("topic1", "topic2")
                )
              )
            )
          _ <- service.refresh(Index.Study)
          a <- service.search(Query.study("name"), from, size)
          b <- service.search(Query.study("study description"), from, size)
          c <- service.search(Query.study("topic1"), from, size)
        yield expect(a.hitIds.size == 1 && b == a && c == a)

  test("game"): _ =>
    Clients
      .search(uri)
      .use: service =>
        for
          _ <- service.mapping(Index.Game)
          _ <- service
            .store(
              "game_id",
              Source.game(
                GameSource(
                  status = 1,
                  turns = 100,
                  rated = true,
                  perf = 1,
                  winnerColor = 1,
                  date = SearchDateTime.fromString("1999-10-20 12:20:20").toOption.get,
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
            )
          _ <- service.refresh(Index.Game)
          a <- service.search(Query.game(List(1)), from, size)
          b <- service.search(Query.game(loser = "uid2".some), from, size)
          c <- service.search(Query.game(), from, size)
          d <- service.search(Query.game(duration = IntRange(a = 99.some, b = 101.some).some), from, size)
          e <- service.search(Query.game(clockInit = 100.some), from, size)
          f <- service.search(Query.game(clockInc = 200.some), from, size)
          g <- service.search(
            Query.game(date = DateRange(a = none, b = Timestamp(1999, 10, 20, 12, 20, 19).some).some),
            from,
            size
          )
        yield expect(a.hitIds.size == 1 && b == a && c == a && d == a && e == a && f == a && g == a)
