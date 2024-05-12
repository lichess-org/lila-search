package lila.search
package app

import cats.effect.IO
import cats.syntax.all.*
import ciris.*
import ciris.http4s.*
import com.comcast.ip4s.*

object AppConfig:

  def load: IO[AppConfig] = appConfig.load[IO]

  def appConfig = (
    HttpServerConfig.config,
    ElasticConfig.config
  ).parMapN(AppConfig.apply)

case class AppConfig(
    server: HttpServerConfig,
    elastic: ElasticConfig
)

case class HttpServerConfig(host: Host, port: Port, apiLogger: Boolean, shutdownTimeout: Int)

object HttpServerConfig:
  private def host   = env("HTTP_HOST").or(prop("http.host")).as[Host].default(ip"0.0.0.0")
  private def port   = env("HTTP_PORT").or(prop("http.port")).as[Port].default(port"9673")
  private def logger = env("HTTP_API_LOGGER").or(prop("http.api.logger")).as[Boolean].default(false)
  private def shutdownTimeout =
    env("HTTP_SHUTDOWN_TIMEOUT").or(prop("http.shutdown.timeout")).as[Int].default(30)
  def config = (host, port, logger, shutdownTimeout).parMapN(HttpServerConfig.apply)

case class ElasticConfig(uri: String)

object ElasticConfig:
  private def uri = env("ELASTIC_URI").or(prop("elastic.uri")).as[String].default("http://127.0.0.1:9200")
  def config      = uri.map(ElasticConfig.apply)
