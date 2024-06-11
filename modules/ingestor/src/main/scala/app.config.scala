package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import ciris.*

case class AppConfig(uri: String, name: String)

object AppConfig:

  private def uri  = env("MONGO_URI").or(prop("mongo.uri")).as[String]
  private def name = env("MONGO_DATABASE").or(prop("mongo.database")).as[String]

  def load = (uri, name).parMapN(AppConfig.apply).load[IO]
