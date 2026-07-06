package lila.search

import lila.search.forum2.Forum2
import weaver.*

object Forum2QueryTest extends FunSuite:

  private def queryString(q: Forum2): String =
    q.searchDef(From(0), Size(10)).query.get.toString

  test("a supported lang queries its body and topic sub-fields"):
    val query = queryString(Forum2("cheval", troll = false, lang = Some("fr")))
    expect(query.contains("bl.fr") && query.contains("tl.fr"))

  test("absent lang defaults to the english sub-fields"):
    val query = queryString(Forum2("knight", troll = false, lang = None))
    expect(query.contains("bl.en") && query.contains("tl.en"))

  test("an unsupported lang falls back to the base fields only"):
    val query = queryString(Forum2("knight", troll = false, lang = Some("tlh")))
    expect(query.contains("bo") && !query.contains("bl."))
