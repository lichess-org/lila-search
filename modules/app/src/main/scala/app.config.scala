package lila.search
package app

import cats.effect.IO
import cats.syntax.all._
import ciris._
import ciris.http4s._
import com.comcast.ip4s._

object AppConfig {

  def load: IO[AppConfig] = appConfig.load[IO]

  def appConfig = (
    HttpServerConfig.config,
    ElasticConfig.config
  ).parMapN(AppConfig.apply)

}

case class AppConfig(
    server: HttpServerConfig,
    elastic: ElasticConfig
)

case class HttpServerConfig(host: Host, port: Port, shutdownTimeout: Int)

object HttpServerConfig {
  private def host = env("HTTP_HOST").or(prop("http.host")).as[Host].default(ip"0.0.0.0")
  private def port = env("HTTP_PORT").or(prop("http.port")).as[Port].default(port"9669")
  private def shutdownTimeout =
    env("HTTP_SHUTDOWN_TIMEOUT").or(prop("http.shutdown.timeout")).as[Int].default(30)
  def config = (host, port, shutdownTimeout).parMapN(HttpServerConfig.apply)
}

case class ElasticConfig(uri: String)

object ElasticConfig {
  private def uri = env("ELASTIC_URI").or(prop("elastic.uri")).as[String]
  def config      = uri.map(ElasticConfig.apply)
}
