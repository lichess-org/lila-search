$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson
use smithy4s.meta#adt
use smithy.api#default
use smithy.api#jsonName

use lila.search.core#Ids
use lila.search.core#FromInt
use lila.search.core#SizeInt
use lila.search.core#PlayerIds
use lila.search.core#Strings
use lila.search.core#IndexString
use lila.search.core#DateTime

@simpleRestJson
service SearchService {
  version: "3.0.0"
  operations: [Search, Count]
}

@readonly
@http(method: "POST", uri: "/api/search/{from}/{size}", code: 200)
operation Search {

  input := {

    @required
    query: Query

    @required
    @httpLabel
    from: FromInt

    @required
    @httpLabel
    size: SizeInt
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
    count: Long
  }

  errors: [InternalServerError]
}

structure Forum {
  @required
  text: String
  @required
  troll: Boolean = false
}

structure Ublog {
  @required
  queryText: String
  @required
  by: SortBlogsBy
  minQuality: Integer
  language: String
}

structure Team {
  @required
  text: String
}

structure Study {
  @required
  text: String
  @required
  sorting: StudySorting
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
  @required
  turns: IntRange
  @required
  averageRating: IntRange
  hasAi: Boolean
  @required
  aiLevel: IntRange
  rated: Boolean
  @required
  date: DateRange
  @required
  duration: IntRange
  @required
  sorting: GameSorting
  analysed: Boolean
  whiteUser: String
  blackUser: String
  clockInit: Integer
  clockInc: Integer
}

structure IntRange {
  a: Integer
  b: Integer
}

structure DateRange {
  a: Timestamp
  b: Timestamp
}

structure GameSorting {
  @required
  f: String
  @required
  order: String
}

list Perfs {
  member: Integer
}

enum SortBlogsBy {
  newest
  oldest
  score
  likes
}

enum Order {
  Asc
  Desc
}

enum StudyOrderBy {
  Likes
  CreatedAt
  UpdatedAt
  Hot
}

structure StudySorting {
  @required
  by: StudyOrderBy
  @required
  order: Order
}

@adt
union Query {
  forum: Forum
  ublog: Ublog
  game: Game
  study: Study
  team: Team
}

@error("server")
@httpError(500)
structure InternalServerError {
  @required
  message: String
}

