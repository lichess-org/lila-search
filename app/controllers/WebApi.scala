package controllers

import akka.actor._
import javax.inject._
import lila.search._
import play.api._
import play.api.http.ContentTypes._
import play.api.libs.json._
import play.api.mvc._

@Singleton
class WebApi @Inject() (protected val system: ActorSystem) extends Controller with WithES {

  def store(index: String, id: String) = JsObjectBody { obj =>
    client.store(Index(index), Id(id), obj) inject Ok(s"inserted $id")
  }

  def deleteById(index: String, id: String) = Action.async {
    client.deleteById(Index(index), Id(id)) inject Ok(s"deleted $id")
  }

  def deleteByQuery(index: String, query: String) = Action.async {
    client.deleteByQuery(Index(index), Query(query)) inject Ok(s"deleted $query")
  }

  def search(index: String, from: Int, size: Int) = JsObjectBody { obj =>
    client.search(Index(index), obj, From(from), Size(size)) map { res =>
      Ok(res.hitIds mkString ",")
    }
  }

  def count(index: String) = JsObjectBody { obj =>
    client.count(Index(index), obj) map { res =>
      Ok(res.count.toString)
    }
  }

  private def JsObjectBody(f: JsObject => Fu[Result]) =
    Action.async(BodyParsers.parse.json) { req =>
      req.body.validate[JsObject].fold(
        err => fuccess(BadRequest(err.toString)),
        obj => f(obj) recover {
          case e: Exception => BadRequest(s"${Json.prettyPrint(obj)}\n\n${e.getMessage}")
        }
      ) map (_ as TEXT)
    }
}
