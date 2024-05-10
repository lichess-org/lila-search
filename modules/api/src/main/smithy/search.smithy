$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson
use smithy4s.meta#adt
use smithy.api#default
use smithy.api#jsonName

@simpleRestJson
service SearchService {
  version: "3.0.0"
  operations: [Search, Count, DeleteById, DeleteByIds, Mapping, Refresh, Store, StoreBulkForum]
}

@readonly
@http(method: "POST", uri: "/search/{from}/{size}", code: 200)
operation Search {
  input: SearchInput
  output: SearchResponse
  errors: [InternalServerError]
}

@readonly
@http(method: "POST", uri: "/count", code: 200)
operation Count {
  input: CountInput
  output: CountResponse
  errors: [InternalServerError]
}

@http(method: "POST", uri: "/delete/id/{index}/{id}", code: 200)
operation DeleteById {
  input: DeleteByIdInput
  errors: [InternalServerError]
}

@http(method: "POST", uri: "/delete/ids/{index}", code: 200)
operation DeleteByIds {
  input: DeleteByIdsInput
  errors: [InternalServerError]
}

@http(method: "POST", uri: "/mapping/{index}", code: 200)
operation Mapping {
  input: MappingInput
  errors: [InternalServerError]
}

@http(method: "POST", uri: "/refresh/{index}", code: 200)
operation Refresh {
  input: RefreshInput
  errors: [InternalServerError]
}

@http(method: "POST", uri: "/store/{id}", code: 200)
operation Store {
  input: StoreInput
  errors: [InternalServerError]
}

@http(method: "POST", uri: "/store-bulk/forum", code: 200)
operation StoreBulkForum {
  input: StoreBulkForumInput
  errors: [InternalServerError]
}

structure SearchInput {

  @required
  query: Query

  @required
  @httpLabel
  from: Integer

  @required
  @httpLabel
  size: Integer
}

structure CountInput {
  @required
  query: Query
}

structure DeleteByIdInput {
  @required
  @httpLabel
  index: Index

  @required
  @httpLabel
  id: String
}

structure DeleteByIdsInput {
  @required
  @httpLabel
  index: Index

  @required
  ids: Ids
}

structure MappingInput {
  @required
  @httpLabel
  index: Index
}

structure RefreshInput {
  @required
  @httpLabel
  index: Index
}

structure StoreInput {

  @required
  source: Source

  @httpLabel
  @required
  id: String
}

structure StoreBulkForumInput {
  @required
  sources: ForumSources
}

list ForumSources {
  member: ForumSourceWithId
}

structure ForumSourceWithId {
  @required
  id: String
  @required
  source: ForumSource
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
  status: String
  @required
  @jsonName("t")
  turns: Integer
  @required
  @jsonName("r")
  rated: Boolean
  @required
  @jsonName("p")
  perf: String
  @jsonName("u")
  uids: Ids
  @jsonName("w")
  winner: String
  @jsonName("o")
  loser: String
  @required
  @jsonName("c")
  winnerColor: Integer
  @required
  @jsonName("a")
  averageRating: Integer
  @required
  @jsonName("i")
  hasAi: Boolean
  @required
  @jsonName("d")
  date: Timestamp // or string?
  @required
  @jsonName("l")
  duration: Integer
  @required
  @jsonName("ct")
  clockInit: Integer
  @jsonName("ci")
  clockInc: Integer
  @jsonName("n")
  analysed: Boolean
  @required
  @jsonName("wu")
  whiteUser: String
  @required
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
