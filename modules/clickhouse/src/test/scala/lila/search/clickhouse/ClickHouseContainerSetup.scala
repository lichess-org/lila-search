package lila.search
package clickhouse

import cats.effect.*
import com.dimafeng.testcontainers.ClickHouseContainer
import doobie.hikari.HikariTransactor
import org.testcontainers.utility.DockerImageName

import scala.concurrent.ExecutionContext

object ClickHouseContainerSetup:

  def resource: Resource[IO, ClickHouseClient[IO]] =
    for
      container <- Resource.make(
        IO(ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8-alpine")))
          .flatTap(c => IO(c.start()))
      )(c => IO(c.stop()))
      xa <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "com.clickhouse.jdbc.ClickHouseDriver",
        url = container.jdbcUrl,
        user = container.username,
        pass = container.password,
        connectEC = ExecutionContext.global
      )
      client = ClickHouseClient.make(xa)
      _ <- Resource.eval(client.createTable)
    yield client
