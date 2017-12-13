package controllers

import akka.actor._
import javax.inject._
import lila.search._
import play.api._
import play.api.http.ContentTypes._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.ExecutionContext

class WebApi @Inject() (cc: ControllerComponents, client: ESClient)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def store(index: String, id: String) = JsObjectBody { obj =>
    client.store(Index(index), Id(id), obj) inject Ok(s"inserted $index/$id")
  }

  def deleteById(index: String, id: String) = Action.async {
    client.deleteById(Index(index), Id(id)) inject Ok(s"deleted $index/$id")
  }

  def deleteByIds(index: String) = JsObjectBody { obj =>
    (obj \ "ids").asOpt[List[String]] match {
      case Some(ids) =>
        client.deleteByIds(Index(index), ids map Id) inject Ok(s"deleted ${ids.size} ids from $index")
      case _ => fuccess(BadRequest(obj))
    }
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

  def mapping(index: String, typ: String) = Action.async {
    Which mapping Index(index, typ) match {
      case None => fuccess(NotFound(s"No such mapping: $index/$typ"))
      case Some(mapping) =>
        client.putMapping(Index(index, typ), mapping) inject Ok(s"put $index/$typ mapping")
    }
  }

  def storeBulk(index: String, typ: String) = JsObjectBody { objs =>
    Chronometer(s"bulk ${objs.fields.size} $index/$typ") {
      client.storeBulk(Index(index, typ), objs) map { _ =>
        Ok("thx")
      }
    }
  }

  def refresh(index: String) = JsObjectBody { obj =>
    client.refreshIndex(Index(index)) map { _ =>
      Ok("thx")
    }
  }

  private def JsObjectBody(f: JsObject => Fu[Result])(implicit ec: ExecutionContext) =
    Action.async(parse.json(maxLength = 10 * 1024 * 1024)) { req =>
      req.body.validate[JsObject].fold(
        err => fuccess(BadRequest(err.toString)),
        obj => f(obj) recover {
          case e: Exception =>
            val msg = s"${Json.prettyPrint(obj)}\n\n${e.getMessage}"
            logger.warn(msg, e)
            BadRequest(msg)
        }
      ) map (_ as TEXT)
    }

  private val logger = play.api.Logger("search")
}
