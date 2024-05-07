package controllers

import javax.inject._
import lila.search._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.ExecutionContext
import lila.search.Index

class WebApi @Inject() (cc: ControllerComponents, client: ESClient)(implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  def store(index: String, id: String) =
    JsObjectBody { obj =>
      client.store(Index(index), Id(id), JsonObject(Json.stringify(obj))) inject Ok(s"inserted $index/$id")
    }

  def deleteById(index: String, id: String) =
    Action.async {
      client.deleteOne(Index(index), Id(id)) inject Ok(s"deleted $index/$id")
    }

  def deleteByIds(index: String) =
    JsObjectBody { obj =>
      (obj \ "ids").asOpt[List[String]] match {
        case Some(ids) =>
          client.deleteMany(Index(index), ids map Id) inject Ok(s"deleted ${ids.size} ids from $index")
        case _ => fuccess(BadRequest(obj))
      }
    }

  def search(index: String, from: Int, size: Int) =
    JsObjectBody { obj =>
      if ((from + size) > 5000) fuccess(BadRequest(s"Too deep: from $from"))
      else
        QueryParser.parse(Index(index))(obj) match {
          case None => fuccess(NotFound(s"Can't parse query for $index"))
          case Some(query) =>
            client.search(Index(index), query, From(from), Size(size)) map { res =>
              Ok(res.hitIds mkString ",")
            }
        }
    }

  def count(index: String) =
    JsObjectBody { obj =>
      QueryParser.parse(Index(index))(obj) match {
        case None => fuccess(NotFound(s"Can't parse query for $index"))
        case Some(query) =>
          client.count(Index(index), query) map { res =>
            Ok(res.count.toString)
          }
      }
    }

  def mapping(index: String) =
    Action.async {
      Which mapping Index(index) match {
        case None => fuccess(NotFound(s"No such mapping: $index"))
        case Some(m) =>
          client.putMapping(Index(index), m) inject Ok(s"put $index mapping")
      }
    }

  def storeBulk(index: String) =
    JsObjectBody { objs =>
      val jsonObjs = objs.fields.collect { case (id, obj) =>
        (id, JsonObject(Json.stringify(obj)))
      }.toList
      Chronometer(s"bulk ${objs.fields.size} $index") {
        client.storeBulk(Index(index), jsonObjs) map { _ =>
          Ok("thx")
        }
      }
    }

  def refresh(index: String) =
    JsObjectBody { _ =>
      client.refreshIndex(Index(index)) map { _ =>
        Ok("thx")
      }
    }

  private def JsObjectBody(f: JsObject => Fu[Result])(implicit ec: ExecutionContext) =
    Action.async(parse.json(maxLength = 10 * 1024 * 1024)) { req =>
      req.body
        .validate[JsObject]
        .fold(
          err => fuccess(BadRequest(err.toString)),
          obj =>
            f(obj) recover { case e: Exception =>
              val msg = s"${Json.prettyPrint(obj)}\n\n${e.getMessage}"
              logger.warn(msg, e)
              BadRequest(msg)
            }
        ) map (_ as TEXT)
    }

  private val logger = play.api.Logger("search")
}
