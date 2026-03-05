package lila.search
package app

import cats.effect.IO
import cats.syntax.all.*
import ciris.*
import ciris.http4s.*
import com.comcast.ip4s.*
import lila.search.clickhouse.ClickHouseConfig
import org.http4s.Uri
import org.http4s.implicits.*

enum GameSearchBackend:
  case ElasticOnly
  case ClickHouseOnly

object AppConfig:

  def load: IO[AppConfig] = appConfig.load[IO]

  def appConfig = (
    HttpServerConfig.config,
    ElasticConfig.config,
    ClickHouseConfig.config,
    gameSearchBackend
  ).parMapN(AppConfig.apply)

  private def gameSearchBackend =
    env("GAME_SEARCH_BACKEND")
      .or(prop("game.search.backend"))
      .as[String]
      .default("elastic")
      .map:
        case "elastic" => GameSearchBackend.ElasticOnly
        case "clickhouse" => GameSearchBackend.ClickHouseOnly

case class AppConfig(
    server: HttpServerConfig,
    elastic: ElasticConfig,
    clickhouse: ClickHouseConfig,
    gameBackend: GameSearchBackend
)

case class HttpServerConfig(
    host: Host,
    port: Port,
    apiLogger: Boolean,
    shutdownTimeout: Int,
    enableDocs: Boolean
)

object HttpServerConfig:
  private def host = env("HTTP_HOST").or(prop("http.host")).as[Host].default(ip"0.0.0.0")
  private def port = env("HTTP_PORT").or(prop("http.port")).as[Port].default(port"9673")
  private def logger = env("HTTP_API_LOGGER").or(prop("http.api.logger")).as[Boolean].default(false)
  private def shutdownTimeout =
    env("HTTP_SHUTDOWN_TIMEOUT").or(prop("http.shutdown.timeout")).as[Int].default(30)
  private def enableDocs = env("HTTP_ENABLE_DOCS").or(prop("http.enable.docs")).as[Boolean].default(false)
  def config = (host, port, logger, shutdownTimeout, enableDocs).parMapN(HttpServerConfig.apply)

case class ElasticConfig(uri: Uri)

object ElasticConfig:
  private def uri =
    env("ELASTIC_URI").or(prop("elastic.uri")).as[Uri].default(uri"http://127.0.0.1:9200")
  def config = uri.map(ElasticConfig.apply)
