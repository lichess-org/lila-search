package lila.search

import com.sksamuel.elastic4s.searches.SearchDefinition

trait Query {

  def searchDef(from: From, size: Size): Index => SearchDefinition

  def countDef: Index => SearchDefinition
}

case class ParsedQuery(terms: List[String], filters: Map[String, String]) {

  def apply(fk: String): Option[String] = filters get fk
}

object QueryParser {

  def apply(q: String, filterKeys: Seq[String]): ParsedQuery = {

    val terms = q.trim.toLowerCase.replace("+", " ").split(" ").toList

    terms.foldLeft(ParsedQuery(Nil, Map.empty)) {
      case (parsed, term) =>
        filterKeys.collectFirst {
          case fk if term startsWith s"$fk:" => parsed.copy(
            filters = parsed.filters + (fk -> term.drop(fk.size + 1))
          )
        } getOrElse parsed.copy(terms = parsed.terms :+ term)
    }
  }
}
