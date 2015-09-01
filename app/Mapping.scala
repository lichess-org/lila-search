package lila.search

import play.api.libs.json._

object Which {

  def mapping(index: Index) = index match {
    case Index("game")  => Some(Game.mapping)
    case Index("forum") => Some(forum.Mapping.fields)
    case Index("team")  => Some(Team.mapping)
    case _              => None
  }

  def query(index: Index)(obj: JsObject): Option[Query] = index match {
    // case Index("game")  => Some(Game.mapping)
    case Index("forum") => forum.Query.jsonReader.reads(obj).asOpt: Option[Query]
    // case Index("team")  => Some(Team.mapping)
    case _              => None
  }
}
