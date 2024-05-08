package lila.search
package app

import cats.effect.*
import org.typelevel.log4cats.Logger
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.cats.effect.instances.*
import com.sksamuel.elastic4s.{ ElasticClient, ElasticProperties }

class AppResources private (val esClient: ESClient[IO])

object AppResources:

  def instance(conf: AppConfig)(using Logger[IO]): Resource[IO, AppResources] =
    makeClient(conf.elastic)
      .map(ESClient.apply[IO])
      .map(AppResources(_))

  def makeClient(conf: ElasticConfig): Resource[IO, ElasticClient] =
    Resource.make(IO(ElasticClient(JavaClient(ElasticProperties(conf.uri))))): client =>
      IO(client.close())
