package lila.search
package app
package test

import cats.effect.{ IO, Resource }
import com.dimafeng.testcontainers.ClickHouseContainer as CHContainer
import doobie.hikari.HikariTransactor
import lila.search.clickhouse.{ ClickHouseClient, ClickHouseConfig }
import org.testcontainers.utility.DockerImageName

import scala.concurrent.ExecutionContext

object ClickHouseContainer:

  def start: Resource[IO, (ClickHouseConfig, ClickHouseClient[IO])] =
    for
      container <- Resource.make(
        IO(CHContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8-alpine")))
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
      config = ClickHouseConfig(container.jdbcUrl, container.username, container.password, 1)
    yield (config, client)
