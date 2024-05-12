$version: "2"

namespace lila.search.spec

use smithy4s.meta#unwrap
use smithy4s.meta#refinement
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

list Strings {
  member: String
}

structure CountResponse {
  @required
  count: Integer
}


@trait(selector: "string")
structure DateTimeFormat {}


apply lila.search.spec#DateTimeFormat @refinement(
   targetType: "lila.search.spec.SearchDateTime"
)

@DateTimeFormat
@unwrap
string DateTime
