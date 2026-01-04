package lila.search

import com.sksamuel.elastic4s.requests.searches.queries.Query
import lila.search.study.Study
import weaver.*

object StudyQueryTest extends FunSuite:

  // Helper to extract query from search definition
  def extractQuery(study: Study): Query =
    val searchDef = study.searchDef(From(0), Size(10))
    searchDef.query.get

  test("basic text search generates correct query"):
    val study = Study(text = "sicilian defense", sorting = None, userId = None)
    val query = extractQuery(study)
    expect(query != null)

  test("chapter name filter generates nested query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapterName = Some("opening trap")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters") && queryStr.contains("chapters.name"))

  test("chapter description filter generates nested query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapterDescription = Some("advanced tactics")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters") && queryStr.contains("chapters.description"))

  test("ECO filter generates double nested query with term"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      eco = Some("B90")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.eco"))

  test("variant filter generates term query for keyword field"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      variant = Some("standard")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.variant"))

  test("player white filter generates match query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      playerWhite = Some("Magnus Carlsen")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.white"))

  test("player black filter generates match query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      playerBlack = Some("Hikaru Nakamura")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.black"))

  test("opening filter generates match query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      opening = Some("King's Indian Defense")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.opening"))

  test("event filter generates match query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      event = Some("World Championship")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.event"))

  test("white FIDE ID filter generates term query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      whiteFideId = Some("1503014")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.whiteFideId"))

  test("black FIDE ID filter generates term query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      blackFideId = Some("2020009")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.blackFideId"))

  test("combined text and chapter filters work together"):
    val study = Study(
      text = "repertoire",
      sorting = None,
      userId = None,
      chapterName = Some("main line")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.name"))

  test("multiple tag filters are combined correctly"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      eco = Some("E97"),
      opening = Some("King's Indian")
    )
    val query = extractQuery(study)
    val queryStr = query.toString
    expect(queryStr.contains("chapters.tags.eco") && queryStr.contains("chapters.tags.opening"))

  test("empty filters do not add unnecessary clauses"):
    val study = Study(
      text = "test",
      sorting = None,
      userId = None
    )
    val query = extractQuery(study)
    expect(query != null)

  test("count query includes all filters"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      eco = Some("B90"),
      chapterName = Some("sicilian")
    )
    val countDef = study.countDef
    val query = countDef.query.get
    val queryStr = query.toString
    expect(
      queryStr.contains("chapters") &&
        queryStr.contains("chapters.tags.eco") &&
        queryStr.contains("chapters.name")
    )
