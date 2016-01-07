package lila.search

import com.sksamuel.elastic4s.{ CountDefinition, SearchDefinition }

trait Query {

  def searchDef(from: From, size: Size): Index => SearchDefinition

  def countDef: Index => SearchDefinition
}
