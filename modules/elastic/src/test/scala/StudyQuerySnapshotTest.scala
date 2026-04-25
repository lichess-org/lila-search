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

  private def snapshotQuery(study: Study, snapshotName: String) =
    val request = study.searchDef(From(0), Size(12))
    val json = request.query.fold("")(QueryBuilderFn(_).string)
    assertFileSnapshot(json, "queries/" + snapshotName)
