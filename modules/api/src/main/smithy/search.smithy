$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson
use smithy4s.meta#adt
use smithy.api#default

@simpleRestJson
service SearchService {
  version: "3.0.0"
  operations: [Search, Count, DeleteById, DeleteByIds, Mapping, Refresh]
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

enum Index {
  Forum = "forum"
  Game = "game"
  Study = "study"
  Team = "team"
}
