package lila.search
package app

import cats.effect.*
import org.typelevel.otel4s.metrics.Meter

class AppResources(val esClient: ESClient[IO])

object AppResources:

  def instance(conf: AppConfig)(using Meter[IO]): Resource[IO, AppResources] =
    ESClient(conf.elastic.uri).map(AppResources.apply)
