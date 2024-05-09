$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson
use smithy4s.meta#adt

@simpleRestJson
service SearchService {
  version: "3.0.0",
  operations: [Search, Count]
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

@adt
union Query {
  forum: Forum
  team: Team
  study: Study
}
