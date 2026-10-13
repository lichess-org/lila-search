package lila.search
package app
package test

import cats.effect.{ IO, Resource }
import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

object ElasticSearchContainer:

  private val PORT = 9200
  private val container =
    val env = Map(
      "discovery.type" -> "single-node",
      "http.cors.allow-origin" -> "/.*/",
      "http.cors.enabled" -> "true",
      "xpack.security.enabled" -> "false"
    )
    val start = IO(
      GenericContainer(
        "elasticsearch:7.10.1",
        exposedPorts = Seq(PORT),
        waitStrategy = Wait.forListeningPort(),
        env = env
      ).configure: cont =>
        // Copy the resource so Docker does not need access to the test runner's filesystem.
        cont.withCopyFileToContainer(
          MountableFile.forClasspathResource("synonyms/chess_synonyms.txt"),
          "/usr/share/elasticsearch/config/synonyms/chess_synonyms.txt"
        ): Unit
    ).flatTap(cont => IO(cont.start()))
    Resource.make(start)(cont => IO(cont.stop()))

  def parseConfig(container: GenericContainer): ElasticConfig =
    ElasticConfig(
      org.http4s.Uri.unsafeFromString(s"http://${container.host}:${container.mappedPort(PORT)}"),
      false
    )

  def start: Resource[IO, ElasticConfig] =
    container.map(parseConfig)
