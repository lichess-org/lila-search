package controllers

import akka.actor._
import javax.inject._
import lila.search._
import play.api._
import play.api.libs.json._
import play.api.mvc._

@Singleton
class WebApi @Inject() (protected val system: ActorSystem) extends Controller with WithES {

  def store(index: String, id: String) = Action.async(BodyParsers.parse.json) { req =>
    req.body.validate[JsObject].fold(
      err => fuccess(BadRequest(err.toString)),
      obj => client.store(Index(index), Id(id), obj) inject Ok(s"inserted $id")
    )
  }

}
