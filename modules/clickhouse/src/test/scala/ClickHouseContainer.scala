package lila.search
package clickhouse

import cats.effect.{ IO, Resource }
import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

object ClickHouseContainer:

  private val HTTP_PORT = 8123
  private val NATIVE_PORT = 9000

  private val container: Resource[IO, GenericContainer] =
    val start = IO:
      GenericContainer(
        dockerImage = "clickhouse/clickhouse-server:24.11-alpine",
        exposedPorts = Seq(HTTP_PORT, NATIVE_PORT),
        waitStrategy = Wait.forListeningPort()
      )
    .flatTap(cont => IO(cont.start()))

    Resource.make(start)(cont => IO(cont.stop()))

  def parseConfig(container: GenericContainer): CHTransactor.Config =
    CHTransactor.Config(
      host = container.host,
      port = container.mappedPort(HTTP_PORT),
      database = "default",
      user = "default",
      password = ""
    )

  def start: Resource[IO, CHTransactor.Config] =
    container.map(parseConfig)
