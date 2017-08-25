package lila.search

import play.api.libs.json._

object Which {

  def mapping(index: Index) = index match {
    case Index(_, "game") => Some(game.Mapping.fields)
    case Index(_, "forum") => Some(forum.Mapping.fields)
    case Index(_, "team") => Some(team.Mapping.fields)
    case Index(_, "study") => Some(study.Mapping.fields)
    case _ => None
  }

  def query(index: Index)(obj: JsObject): Option[Query] = index match {
    case Index(_, "game") => game.Query.jsonReader.reads(obj).asOpt: Option[Query]
    case Index(_, "forum") => forum.Query.jsonReader.reads(obj).asOpt: Option[Query]
    case Index(_, "team") => team.Query.jsonReader.reads(obj).asOpt: Option[Query]
    case Index(_, "study") => study.Query.jsonReader.reads(obj).asOpt: Option[study.Query]
    case _ => None
  }

  def refreshInterval(index: Index) = index match {
    case Index(_, "study") => "10s"
    case _ => "300s"
  }
}
