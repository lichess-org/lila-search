package controllers

import lila.search._

import akka.actor._
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.{ ElasticClient, ElasticsearchClientUri }
import org.elasticsearch.common.settings.ImmutableSettings
import scala.concurrent.duration._

trait WithES {

  protected def system: ActorSystem
  protected def lifecycle: play.api.inject.ApplicationLifecycle
  private def config = play.api.Play.current.configuration

  private lazy val IndexesToOptimize = List("game", "forum", "team")
  private lazy val ElasticsearchUri = config getString "elasticsearch.uri" getOrElse {
    sys error "Missing config for elasticsearch.uri"
  }

  lazy val underlyingClient: ElasticClient = {

    val c = ElasticClient.remote(ElasticsearchClientUri(ElasticsearchUri))

    lifecycle.addStopHook(() => scala.concurrent.Future {
      play.api.Logger("search").info("closing now!")
      c.close()
      Thread sleep 1000
    })

    c
  }

  lazy val client = new ESClient(underlyingClient)

  system.scheduler.schedule(1 hour, 1 hour) {
    underlyingClient execute {
      optimize index IndexesToOptimize
    }
  }
}
