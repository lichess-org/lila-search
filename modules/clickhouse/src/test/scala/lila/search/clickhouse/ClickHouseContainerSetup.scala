package lila.search
package clickhouse

import cats.effect.*
import com.dimafeng.testcontainers.ClickHouseContainer
import org.testcontainers.utility.DockerImageName

object ClickHouseContainerSetup:

  def resource: Resource[IO, ClickHouseClient[IO]] =
    for
      container <- Resource.make(
        IO(ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8-alpine")))
          .flatTap(c => IO(c.start()))
      )(c => IO(c.stop()))
      config = ClickHouseConfig(container.jdbcUrl, container.username, container.password, 1, 1_073_741_824L)
      xa <- ClickHouseTransactor.make(config)
      client = ClickHouseClient.make(xa)
      _ <- Resource.eval(client.createTable)
    yield client
