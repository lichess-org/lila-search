package lila.search
package app

import cats.effect.*

class AppResources(val esClient: ESClient[IO])

object AppResources:

  def instance(conf: AppConfig): Resource[IO, AppResources] =
    ESClient(conf.elastic.uri).map(AppResources.apply)
