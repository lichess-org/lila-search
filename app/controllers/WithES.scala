package controllers

import lila.search._

import akka.actor._
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.ElasticsearchClientUri
import scala.concurrent.duration._

trait WithES {

  protected def system: ActorSystem
  protected def lifecycle: play.api.inject.ApplicationLifecycle
  protected def config: play.api.Configuration

  private lazy val IndexesToOptimize = List("game", "forum", "team", "study")
  private lazy val ElasticsearchUri = config.get[String]("elasticsearch.uri")

  lazy val underlyingClient: HttpClient = {

    val c = HttpClient(ElasticsearchClientUri(ElasticsearchUri))

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
      forceMerge(IndexesToOptimize)
    }
  }
}
