package lila.search

import lila.search.ublog.Ublog
import weaver.*

object UblogQueryTest extends FunSuite:

  private def queryString(ublog: Ublog): String =
    ublog.searchDef(From(0), Size(10)).query.get.toString

  test("search only returns posts marked visible"):
    val query = queryString(Ublog("puzzle", Ublog.SortBy.newest, None, None))
    expect(query.contains("visible") && query.contains("true"))
