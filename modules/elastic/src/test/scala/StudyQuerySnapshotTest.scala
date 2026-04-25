package lila.search

import com.sksamuel.elastic4s.handlers.searches.queries.QueryBuilderFn
import lila.search.study.Study
import snapshot4s.generated.*
import snapshot4s.weaver.SnapshotExpectations
import weaver.*

object StudyQuerySnapshotTest extends SimpleIOSuite with SnapshotExpectations:

  test("legacy query shape: public text query"):
    snapshotQuery(Study("hello world", None, None), "study_legacy_public.json")

  test("legacy query shape: owner-prefixed query"):
    snapshotQuery(Study("owner:bob foo", None, None), "study_legacy_owner.json")

  test("legacy query shape: member-prefixed query with userId"):
    snapshotQuery(Study("member:alice", None, Some("alice")), "study_legacy_member.json")

  test("filter shape: single chapterName"):
    snapshotQuery(
      Study("", None, None, chapterName = Some("opening trap")),
      "study_filter_chapter_name.json"
    )

  test("filter shape: both chapter filters"):
    snapshotQuery(
      Study(
        "",
        None,
        None,
        chapterName = Some("main line"),
        chapterDescription = Some("advanced tactics")
      ),
      "study_filter_chapter_both.json"
    )

  test("filter shape: multiple tag filters combined"):
    snapshotQuery(
      Study(
        "",
        None,
        None,
        variant = Some("standard"),
        eco = Some("B90"),
        whiteFideId = Some("1503014"),
        blackFideId = Some("2020009"),
        opening = Some("Sicilian Najdorf"),
        playerWhite = Some("Magnus Carlsen"),
        playerBlack = Some("Hikaru Nakamura"),
        event = Some("World Championship")
      ),
      "study_filter_tag_multi.json"
    )

  test("filter shape: chapter and tag combined"):
    snapshotQuery(
      Study(
        "",
        None,
        None,
        chapterName = Some("repertoire"),
        eco = Some("E97")
      ),
      "study_filter_chapter_and_tag.json"
    )

  test("filter shape: text plus chapter filter (public branch)"):
    snapshotQuery(
      Study("repertoire", None, None, chapterName = Some("main line")),
      "study_filter_text_chapter_public.json"
    )

  test("filter shape: owner-prefixed text plus tag filter"):
    snapshotQuery(
      Study("owner:bob foo", None, None, eco = Some("B90")),
      "study_filter_text_tag_owner.json"
    )

  test("filter shape: chapter filter with userId set"):
    snapshotQuery(
      Study("", None, Some("alice"), chapterName = Some("openings")),
      "study_filter_with_user_id.json"
    )

  private def snapshotQuery(study: Study, snapshotName: String) =
    val request = study.searchDef(From(0), Size(12))
    val json = request.query.fold("")(QueryBuilderFn(_).string)
    assertFileSnapshot(json, "queries/" + snapshotName)
