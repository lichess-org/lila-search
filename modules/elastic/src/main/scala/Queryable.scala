package lila.search

import com.sksamuel.elastic4s.requests.count.CountRequest
import com.sksamuel.elastic4s.requests.searches.SearchRequest

trait Queryable[A]:

  def searchDef(query: A)(from: SearchFrom, size: SearchSize): SearchRequest

  def countDef(query: A): CountRequest

case class ParsedQuery(terms: List[String], filters: Map[String, String]):

  def apply(fk: String): Option[String] = filters.get(fk)

object QueryParser:

  private val spaceRegex = "[ +]+".r

  def apply(q: String, filterKeys: Seq[String]): ParsedQuery =

    val terms = spaceRegex.split(q.trim.toLowerCase).toList

    terms.foldLeft(ParsedQuery(Nil, Map.empty)) { case (parsed, term) =>
      filterKeys
        .collectFirst {
          case fk if term.startsWith(s"$fk:") =>
            parsed.copy(
              filters = parsed.filters + (fk -> term.drop(fk.size + 1))
            )
        }
        .getOrElse(parsed.copy(terms = parsed.terms :+ term))
    }
