package lila.search

import com.sksamuel.elastic4s.handlers.searches.queries.QueryBuilderFn
import lila.search.study.Study
import lila.search.study.Study.{ ChapterMode, TagFilter }
import snapshot4s.generated.*
import snapshot4s.weaver.SnapshotExpectations
import weaver.*

object StudyQuerySnapshotTest extends SimpleIOSuite with SnapshotExpectations:

  private def filters(cf: TagFilter): Option[ChapterMode] =
    Some(ChapterMode.Filters(cf))

  test("legacy query shape: public text query"):
    snapshotQuery(Study("hello world", None, None), "study_legacy_public.json")

  test("legacy query shape: owner-prefixed query"):
    snapshotQuery(Study("owner:bob foo", None, None), "study_legacy_owner.json")

  test("legacy query shape: member-prefixed query with userId"):
    snapshotQuery(Study("member:alice", None, Some("alice")), "study_legacy_member.json")

  test("filter shape: multiple tag filters combined"):
    snapshotQuery(
      Study(
        "",
        None,
        None,
        chapter = filters(
          TagFilter(
            variant = Some("standard"),
            eco = Some("B90"),
            whiteFideId = Some("1503014"),
            blackFideId = Some("2020009"),
            opening = Some("Sicilian Najdorf"),
            playerWhite = Some("Magnus Carlsen"),
            playerBlack = Some("Hikaru Nakamura"),
            event = Some("World Championship")
          )
        )
      ),
      "study_filter_tag_multi.json"
    )

  test("filter shape: text plus tag filter (public branch)"):
    snapshotQuery(
      Study("repertoire", None, None, chapter = filters(TagFilter(eco = Some("B90")))),
      "study_filter_text_tag_public.json"
    )

  test("filter shape: owner-prefixed text plus tag filter"):
    snapshotQuery(
      Study("owner:bob foo", None, None, chapter = filters(TagFilter(eco = Some("B90")))),
      "study_filter_text_tag_owner.json"
    )

  test("filter shape: tag filter with userId set"):
    snapshotQuery(
      Study("", None, Some("alice"), chapter = filters(TagFilter(eco = Some("E97")))),
      "study_filter_with_user_id.json"
    )

  test("mode 2: SearchText runs full chapter query"):
    snapshotQuery(
      Study("sicilian", None, None, chapter = Some(ChapterMode.SearchText)),
      "study_chapter_searchtext.json"
    )

  test("mode 2: SearchText with owner prefix"):
    snapshotQuery(
      Study("owner:bob sicilian", None, None, chapter = Some(ChapterMode.SearchText)),
      "study_chapter_searchtext_owner.json"
    )

  private def snapshotQuery(study: Study, snapshotName: String) =
    val request = study.searchDef(From(0), Size(12))
    val json = request.query.fold("")(QueryBuilderFn(_).string)
    assertFileSnapshot(json, "queries/" + snapshotName)
