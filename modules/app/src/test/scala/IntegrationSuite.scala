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
          _ <- service.mapping(lila.search.spec.Index.Forum)
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
        yield expect(x.hitIds.size == 1)
