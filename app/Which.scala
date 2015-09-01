package lila.search

import play.api.libs.json._

object Which {

  def mapping(index: Index) = index match {
    case Index("game")  => Some(game.Mapping.fields)
    case Index("forum") => Some(forum.Mapping.fields)
    case Index("team")  => Some(team.Mapping.fields)
    case _              => None
  }

  def query(index: Index)(obj: JsObject): Option[Query] = index match {
    case Index("game")  => game.Query.jsonReader.reads(obj).asOpt: Option[Query]
    case Index("forum") => forum.Query.jsonReader.reads(obj).asOpt: Option[Query]
    case Index("team")  => team.Query.jsonReader.reads(obj).asOpt: Option[Query]
    case _              => None
  }
}
