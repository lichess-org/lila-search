package controllers

import lila.search._

import akka.actor._
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl._
import org.elasticsearch.common.settings.ImmutableSettings
import scala.concurrent.duration._

trait WithES {

  protected def system: ActorSystem
  protected def config = play.api.Play.current.configuration

  private val IndexesToOptimize = List("game", "forum", "team")
  private val ElasticHome = config getString "elasticsearch.home" getOrElse {
    sys error "Missing config for elasticsearch.home"
  }

  val underlyingClient: ElasticClient = {
    val settings = ImmutableSettings.settingsBuilder()
      .put("http.enabled", false)
      .put("path.home", ElasticHome)
    ElasticClient.local(settings.build)
  }

  val client = new ESClient(underlyingClient)

  system.scheduler.schedule(1 hour, 1 hour) {
    underlyingClient execute {
      optimize index IndexesToOptimize
    }
  }
}
