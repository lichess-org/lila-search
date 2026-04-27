package lila.search

import com.sksamuel.elastic4s.requests.searches.queries.Query
import lila.search.study.Study
import lila.search.study.Study.{ ChapterMode, TagFilter }
import weaver.*

object StudyQueryTest extends FunSuite:

  // Helper to extract query from search definition
  def extractQuery(study: Study): Query =
    val searchDef = study.searchDef(From(0), Size(10))
    searchDef.query.get

  private def filters(cf: TagFilter): Option[ChapterMode] =
    Some(ChapterMode.Filters(cf))

  test("mode 2 (SearchText) runs nested chapter query"):
    val study = Study(
      text = "sicilian",
      sorting = None,
      userId = None,
      chapter = Some(ChapterMode.SearchText)
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters") && queryStr.contains("chapters.name"))

  test("ECO filter generates double nested query with term"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(eco = Some("B90")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.eco"))

  test("variant filter generates term query for keyword field"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(variant = Some("standard")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.variant"))

  test("player1 filter matches either color"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(player1 = Some("Magnus Carlsen")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.white") && queryStr.contains("chapters.tags.black"))

  test("player2 filter matches either color"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(player2 = Some("Hikaru Nakamura")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.white") && queryStr.contains("chapters.tags.black"))

  test("player1 + player2 filters match symmetrically"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(player1 = Some("Carlsen"), player2 = Some("Nakamura")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("Carlsen") && queryStr.contains("Nakamura"))

  test("opening filter generates match query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(opening = Some("King's Indian Defense")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.opening"))

  test("event filter generates match query"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(event = Some("World Championship")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.event"))

  test("fideId1 filter matches either color"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(fideId1 = Some("1503014")))
    )
    val queryStr = extractQuery(study).toString
    expect(
      queryStr.contains("chapters.tags.whiteFideId") && queryStr.contains("chapters.tags.blackFideId")
    )

  test("fideId1 + fideId2 filters match symmetrically"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(fideId1 = Some("1503014"), fideId2 = Some("2020009")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("1503014") && queryStr.contains("2020009"))

  test("combined text and tag filters work together"):
    val study = Study(
      text = "repertoire",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(eco = Some("E97")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.eco"))

  test("multiple tag filters are combined correctly"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(eco = Some("E97"), opening = Some("King's Indian")))
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.tags.eco") && queryStr.contains("chapters.tags.opening"))

  test("Filters with all-None fields adds only chapter name/description should clause"):
    val study = Study(
      text = "test",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter())
    )
    val queryStr = extractQuery(study).toString
    expect(queryStr.contains("chapters.name") && queryStr.contains("chapters.description"))

  test("count query includes all filters"):
    val study = Study(
      text = "",
      sorting = None,
      userId = None,
      chapter = filters(TagFilter(eco = Some("B90"), variant = Some("standard")))
    )
    val countDef = study.countDef
    val queryStr = countDef.query.get.toString
    expect(
      queryStr.contains("chapters") &&
        queryStr.contains("chapters.tags.eco") &&
        queryStr.contains("chapters.tags.variant")
    )
