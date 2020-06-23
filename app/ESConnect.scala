import lila.search._

import akka.actor._
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.{ ElasticClient, ElasticProperties }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object ESConnect {

  def apply(
      system: ActorSystem,
      lifecycle: play.api.inject.ApplicationLifecycle,
      config: play.api.Configuration
  )(implicit ec: ExecutionContext): ESClient = {

    val IndexesToOptimize = List("game", "forum", "team", "study")

    val underlyingClient: ElasticClient = {

      val c = ElasticClient(JavaClient(ElasticProperties(config.get[String]("elasticsearch.uri"))))

      lifecycle.addStopHook(() =>
        scala.concurrent.Future {
          play.api.Logger("search").info("closing now!")
          c.close()
          Thread sleep 1000
        }
      )

      c
    }

    system.scheduler.scheduleWithFixedDelay(1 hour, 1 hour) { () =>
      underlyingClient execute {
        forceMerge(IndexesToOptimize)
      }
    }

    new ESClient(underlyingClient)
  }
}
