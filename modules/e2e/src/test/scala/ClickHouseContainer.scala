package lila.search
package app
package test

import cats.effect.{ IO, Resource }
import com.dimafeng.testcontainers.ClickHouseContainer as CHContainer
import lila.search.clickhouse.{ ClickHouseClient, ClickHouseConfig, ClickHouseTransactor }
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

object ClickHouseContainer:

  def start: Resource[IO, (ClickHouseConfig, ClickHouseClient[IO])] =
    for
      container <- Resource.make(
        IO(CHContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8-alpine")))
          .flatTap(c => IO(c.start()))
      )(c => IO(c.stop()))
      config = ClickHouseConfig(
        container.jdbcUrl,
        container.username,
        container.password,
        1,
        1_073_741_824L,
        30.seconds
      )
      xa <- ClickHouseTransactor.make(config)
      client = ClickHouseClient.make(xa)
      _ <- Resource.eval(client.createAllTables)
    yield (config, client)
