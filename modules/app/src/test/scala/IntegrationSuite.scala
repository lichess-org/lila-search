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

object IntegrationSuite extends IOSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val uri = Uri.unsafeFromString("http://localhost:9999")

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
                  date = Timestamp(2021, 1, 1, 0, 0, 0),
                  author = "nt9".some
                )
              )
            )
          _ <- service.refresh(Index.Forum)
          x <- service.search(Query.forum("chess", false), 0, 12)
          y <- service.search(Query.forum("nt9", false), 0, 12)
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
          x <- service.search(Query.team("team name"), 0, 12)
          y <- service.search(Query.team("team description"), 0, 12)
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
                  chapterNames = "chapter names",
                  chapterTexts = "chapter texts",
                  likes = 100,
                  public = true,
                  topics = List("topic1", "topic2")
                )
              )
            )
          _ <- service.refresh(Index.Study)
          x <- service.search(Query.study("study name"), 0, 12)
          y <- service.search(Query.study("study description"), 0, 12)
          z <- service.search(Query.study("topic1"), 0, 12)
        yield expect(x.hitIds.size == 1 && x == y && z == x)

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
          x <- service.search(Query.game(List(1)), 0, 12)
          y <- service.search(Query.game(loser = "uid2".some), 0, 12)
          z <- service.search(Query.game(), 0, 12)
          w <- service.search(Query.game(duration = IntRange(a = 99.some, b = 101.some).some), 0, 12)
        yield expect(x.hitIds.size == 1 && x == y && z == x && w == x)
