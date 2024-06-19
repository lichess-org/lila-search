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

list Ids {
  member: String
}

list Strings {
  member: String
}

@trait(selector: "string")
@refinement(
   targetType: "lila.search.SearchDateTime"
   providerImport: "lila.search.spec.providers.given"
)
structure DateTimeFormat {}

@DateTimeFormat
@unwrap
string DateTime
