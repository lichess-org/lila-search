package controllers

import akka.actor._
import javax.inject._
import lila.search._
import play.api._
import play.api.http.ContentTypes._
import play.api.libs.json._
import play.api.mvc._

@Singleton
class WebApi @Inject() (
    protected val system: ActorSystem,
    protected val lifecycle: play.api.inject.ApplicationLifecycle) extends Controller with WithES {

  def store(index: String, id: String) = JsObjectBody { obj =>
    client.store(Index(index), Id(id), obj) inject Ok(s"inserted $index/$id")
  }

  def storeBulk(index: String) = JsObjectBody { objs =>
    Chronometer(s"bulk ${objs.fields.size} $index") {
      client.storeBulk(Index(index), objs)
    }
    fuccess(Ok(s"bulk inserted $index")) // async!
  }

  def deleteById(index: String, id: String) = Action.async {
    client.deleteById(Index(index), Id(id)) inject Ok(s"deleted $index/$id")
  }

  def deleteByQuery(index: String, query: String) = Action.async {
    client.deleteByQuery(Index(index), StringQuery(query)) inject Ok(s"deleted $index/$query")
  }

  def search(index: String, from: Int, size: Int) = JsObjectBody { obj =>
    Which.query(Index(index))(obj) match {
      case None => fuccess(NotFound(s"Can't parse query for $index"))
      case Some(query) => client.search(Index(index), query, From(from), Size(size)) map { res =>
        Ok(res.hitIds mkString ",")
      }
    }
  }

  def count(index: String) = JsObjectBody { obj =>
    Which.query(Index(index))(obj) match {
      case None => fuccess(NotFound(s"Can't parse query for $index"))
      case Some(query) => client.count(Index(index), query) map { res =>
        Ok(res.count.toString)
      }
    }
  }

  def mapping(index: String) = Action.async {
    Which mapping Index(index) match {
      case None => fuccess(NotFound(s"No such index: $index"))
      case Some(mapping) =>
        client.putMapping(Index(index), mapping) inject Ok(s"put $index mapping")
    }
  }

  private def JsObjectBody(f: JsObject => Fu[Result]) =
    Action.async(BodyParsers.parse.json(maxLength = 10 * 1024 * 1024)) { req =>
      req.body.validate[JsObject].fold(
        err => fuccess(BadRequest(err.toString)),
        obj => f(obj) recover {
          case e: Exception =>
            val msg = s"${Json.prettyPrint(obj)}\n\n${e.getMessage}"
            logger warn msg
            BadRequest(msg)
        }
      ) map (_ as TEXT)
    }

  private val logger = play.api.Logger("search")
}
