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

@trait(selector: "string")
@refinement(
   targetType: "lila.search.Id"
   providerImport: "lila.search.spec.providers.given"
)
structure IdFormat {}

@IdFormat
@unwrap
string IdString

list Ids {
  member: IdString
}

list Strings {
  member: String
}

list PlayerIds {
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

@trait(selector: "integer")
@refinement(
   targetType: "lila.search.Size"
   providerImport: "lila.search.spec.providers.given"
)
structure SizeFormat {}

@SizeFormat
@unwrap
integer SizeInt

@trait(selector: "integer")
@refinement(
   targetType: "lila.search.From"
   providerImport: "lila.search.spec.providers.given"
)
structure FromFormat {}

@FromFormat
@unwrap
integer FromInt

@trait(selector: "string")
@refinement(
   targetType: "lila.search.Index"
   providerImport: "lila.search.spec.providers.given"
)
structure IndexFormat {}

@IndexFormat
@unwrap
string IndexString
