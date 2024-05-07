$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson

@error("server")
@httpError(500)
structure InternalServerError {
  @required
  message: String
}

structure SearchResponse {
  @required
  hitIds: Ids
}

list Ids {
  member: String
}

structure CountResponse {
  @required
  count: Integer
}
