package controllers

import lila.search._

import akka.actor._
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl._
import org.elasticsearch.common.settings.ImmutableSettings
import scala.concurrent.duration._

trait WithES {

  protected def system: ActorSystem
  protected def lifecycle: play.api.inject.ApplicationLifecycle
  private def config = play.api.Play.current.configuration

  private lazy val IndexesToOptimize = List("game", "forum", "team")
  private lazy val ElasticHome = config getString "elasticsearch.home" getOrElse {
    sys error "Missing config for elasticsearch.home"
  }
  private lazy val ElasticHTTP = config getBoolean "elasticsearch.http" getOrElse false

  lazy val underlyingClient: ElasticClient = {
    val settings = ImmutableSettings.settingsBuilder()
      .put("bootstrap.mlockall", true) // prevent swapping
      .put("http.enabled", ElasticHTTP)
      .put("path.home", ElasticHome)
      .put("path.logs", s"$ElasticHome/logs")
      .put("path.data", s"$ElasticHome/data")
      .put("index.number_of_shards", 1)
      .put("index.number_of_replicas", 0)

    val c = ElasticClient.local(settings.build)

    lifecycle.addStopHook(() => fuccess(c.close()))

    c
  }

  lazy val client = new ESClient(underlyingClient)

  system.scheduler.schedule(1 hour, 1 hour) {
    underlyingClient execute {
      optimize index IndexesToOptimize
    }
  }
}
