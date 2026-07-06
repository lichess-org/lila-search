package lila.search

import weaver.*

object SearchLangTest extends FunSuite:

  // the language analyzers Elasticsearch 7.10 ships built-in
  private val es710Analyzers = Set(
    "arabic",
    "armenian",
    "basque",
    "bengali",
    "brazilian",
    "bulgarian",
    "catalan",
    "cjk",
    "czech",
    "danish",
    "dutch",
    "english",
    "estonian",
    "finnish",
    "french",
    "galician",
    "german",
    "greek",
    "hindi",
    "hungarian",
    "indonesian",
    "irish",
    "italian",
    "latvian",
    "lithuanian",
    "norwegian",
    "persian",
    "portuguese",
    "romanian",
    "russian",
    "sorani",
    "spanish",
    "swedish",
    "thai",
    "turkish"
  )

  test("every mapped analyzer exists in ES 7.10"):
    expect(SearchLang.byCode.values.toSet.subsetOf(es710Analyzers))

  test("chinese, japanese and korean map to cjk"):
    expect(List("zh", "ja", "ko").flatMap(c => SearchLang.byCode.get(c)) == List("cjk", "cjk", "cjk"))

  test("supported code passes through, unsupported and absent return None"):
    expect.all(
      SearchLang.esLangKey(Some("fr")) == Some("fr"),
      SearchLang.esLangKey(Some("tlh")) == None,
      SearchLang.esLangKey(None) == None
    )

  test("sortedCodes is deterministic and complete"):
    expect(SearchLang.sortedCodes == SearchLang.byCode.toList.sortBy(_._1))
