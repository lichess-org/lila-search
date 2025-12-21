package lila.search

import com.sksamuel.elastic4s.handlers.index.CreateIndexContentBuilder
import snapshot4s.generated.*
import snapshot4s.weaver.SnapshotExpectations
import weaver.*

object MappingGeneratorSuite extends SimpleIOSuite with SnapshotExpectations:

  test("Study index"):
    testIndex(Index.Study, "study.json")

  test("Game index"):
    testIndex(Index.Game, "game.json")

  test("Forum index"):
    testIndex(Index.Forum, "forum.json")

  test("Ublog index"):
    testIndex(Index.Ublog, "ublog.json")

  test("Team index"):
    testIndex(Index.Team, "team.json")

  def testIndex(index: Index, snapshotName: String) =
    val request = index.createIndexRequest
    val json = CreateIndexContentBuilder(request).string
    assertFileSnapshot(json, "mappings/" + snapshotName)
