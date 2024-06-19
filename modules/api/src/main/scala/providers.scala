package lila.search
package spec

import smithy4s.*

object providers:

  given RefinementProvider[DateTimeFormat, String, SearchDateTime] =
    Refinement.drivenBy(SearchDateTime.fromString, _.value)
