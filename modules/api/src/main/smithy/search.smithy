$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson
use smithy4s.meta#adt
use smithy.api#default
use smithy.api#jsonName

@simpleRestJson
service SearchService {
  version: "3.0.0"
  operations: [Search, Count, DeleteById, DeleteByIds, Mapping, Refresh, Store, StoreBulkForum, StoreBulkGame, StoreBulkStudy, StoreBulkTeam]
}

@readonly
@http(method: "POST", uri: "/api/search/{from}/{size}", code: 200)
operation Search {

  input := {

    @required
    query: Query

    @required
    @httpLabel
    from: Integer

    @required
    @httpLabel
    size: Integer
  }

  output := {
    @required
    hitIds: Ids
  }

  errors: [InternalServerError]
}

@readonly
@http(method: "POST", uri: "/api/count", code: 200)
operation Count {
  input := {
    @required
    query: Query
  }

  output := {
    @required
    count: Integer
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/delete/id/{index}/{id}", code: 200)
operation DeleteById {
  input := {
    @required
    @httpLabel
    index: Index

    @required
    @httpLabel
    id: String
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/delete/ids/{index}", code: 200)
operation DeleteByIds {
  input := {
    @required
    @httpLabel
    index: Index

    @required
    ids: Ids
  }
  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/mapping/{index}", code: 200)
operation Mapping {
  input := {
    @required
    @httpLabel
    index: Index
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/refresh/{index}", code: 200)
operation Refresh {
  input := {
    @required
    @httpLabel
    index: Index
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/store/{id}", code: 200)
operation Store {
  input := {

    @httpLabel
    @required
    id: String

    @required
    source: Source
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/store-bulk/forum", code: 200)
operation StoreBulkForum {
  input := {
    @required
    sources: ForumSources
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/store-bulk/game", code: 200)
operation StoreBulkGame {
  input := {
    @required
    sources: GameSources
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/store-bulk/study", code: 200)
operation StoreBulkStudy {
  input := {
    @required
    sources: StudySources
  }

  errors: [InternalServerError]
}

@http(method: "POST", uri: "/api/store-bulk/team", code: 200)
operation StoreBulkTeam {
  input := {
    @required
    sources: TeamSources
  }

  errors: [InternalServerError]
}

list ForumSources {
  member: ForumSourceWithId
}

list GameSources {
  member: GameSourceWithId
}

list StudySources {
  member: StudySourceWithId
}

list TeamSources {
  member: TeamSourceWithId
}

structure ForumSourceWithId {
  @required
  id: String
  @required
  source: ForumSource
}

structure TeamSourceWithId {
  @required
  id: String
  @required
  source: TeamSource
}

structure StudySourceWithId {
  @required
  id: String
  @required
  source: StudySource
}

structure GameSourceWithId {
  @required
  id: String
  @required
  source: GameSource
}

structure Forum {
  @required
  text: String
  @required
  troll: Boolean = false
}

structure Team {
  @required
  text: String
}

structure Study {
  @required
  text: String
  userId: String
}

structure Game {
  user1: String
  user2: String
  winner: String
  loser: String
  winnerColor: Integer
  @default
  perf: Perfs
  source: Integer
  status: Integer
  turns: IntRange
  averageRating: IntRange
  hasAi: Boolean
  aiLevel: IntRange
  rated: Boolean
  date: DateRange
  duration: IntRange
  clock: Clocking
  sorting: Sorting
  analysed: Boolean
  whiteUser: String
  blackUser: String
  clockInit: Integer
}

structure IntRange {
  a: Integer
  b: Integer
}

structure DateRange {
  a: Timestamp
  b: Timestamp
}

structure Clocking {
  initMin: Integer
  initMax: Integer
  incMin: Integer
  incMax: Integer
}

structure Sorting {
  @required
  f: String
  @required
  order: String
}

list Perfs {
  member: Integer
}

@adt
union Query {
  forum: Forum
  game: Game
  study: Study
  team: Team
}

structure ForumSource {
  @required
  @jsonName("bo")
  body: String
  @required
  @jsonName("to")
  topic: String
  @required
  @jsonName("ti")
  topicId: String
  @jsonName("au")
  author: String
  @required
  @jsonName("tr")
  troll: Boolean
  @required
  @jsonName("da")
  date: Timestamp
}

structure GameSource {
  @required
  @jsonName("s")
  status: Integer
  @required
  @jsonName("t")
  turns: Integer
  @required
  @jsonName("r")
  rated: Boolean
  @required
  @jsonName("p")
  perf: Integer
  @jsonName("u")
  uids: Ids
  @jsonName("w")
  winner: String
  @jsonName("o")
  loser: String
  @required
  @jsonName("c")
  winnerColor: Integer
  @jsonName("a")
  averageRating: Integer
  @jsonName("i")
  ai: Integer
  @required
  @jsonName("d")
  date: DateTime
  @jsonName("l")
  duration: Integer
  @jsonName("ct")
  clockInit: Integer
  @jsonName("ci")
  clockInc: Integer
  @required
  @jsonName("n")
  analysed: Boolean
  @jsonName("wu")
  whiteUser: String
  @jsonName("bu")
  blackUser: String
  @jsonName("so")
  source: Integer
}

structure StudySource {
  @required
  name: String
  @required
  owner: String
  @required
  members: Ids
  @required
  chapterNames: String

  @required
  chapterTexts: String
  @default
  topics: Strings
  @required
  likes: Integer
  @required
  public: Boolean
}

structure TeamSource {
  @required
  @jsonName("na")
  name: String
  @required
  @jsonName("de")
  description: String
  @required
  @jsonName("nbm")
  nbMembers: Integer
}

union Source {
  forum: ForumSource
  game: GameSource
  study: StudySource
  team: TeamSource
}

enum Index {
  Forum = "forum"
  Game = "game"
  Study = "study"
  Team = "team"
}
