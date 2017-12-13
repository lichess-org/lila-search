import lila.search._

import akka.actor._
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object ESConnect {

  def apply(
    system: ActorSystem,
    lifecycle: play.api.inject.ApplicationLifecycle,
    config: play.api.Configuration
  )(implicit ec: ExecutionContext): ESClient = {

    val IndexesToOptimize = List("game", "forum", "team", "study")
    val Host = config.get[String]("elasticsearch.host")
    val Port = config.get[Int]("elasticsearch.port")

    val underlyingClient: HttpClient = {

      val c = HttpClient(ElasticsearchClientUri(Host, Port))

      lifecycle.addStopHook(() => scala.concurrent.Future {
        play.api.Logger("search").info("closing now!")
        c.close()
        Thread sleep 1000
      })

      c
    }

    system.scheduler.schedule(1 hour, 1 hour) {
      underlyingClient execute {
        forceMerge(IndexesToOptimize)
      }
    }

    new ESClient(underlyingClient)
  }
}
