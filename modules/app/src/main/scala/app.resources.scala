package lila.search
package app

import cats.effect.*
import org.typelevel.log4cats.Logger

class AppResources(val esClient: ESClient[IO])

object AppResources:

  def instance(conf: AppConfig)(using Logger[IO]): Resource[IO, AppResources] =
    ESClient.apply(conf.elastic.uri).map(AppResources.apply)
