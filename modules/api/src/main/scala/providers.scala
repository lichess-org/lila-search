package lila.search
package spec

import smithy4s.*

object providers:

  given RefinementProvider[DateTimeFormat, String, SearchDateTime] =
    Refinement.drivenBy(SearchDateTime.fromString, _.value)

  given RefinementProvider[SizeFormat, Int, SearchSize] =
    Refinement.drivenBy(SearchSize.apply, _.value)

  given RefinementProvider[FromFormat, Int, SearchFrom] =
    Refinement.drivenBy(SearchFrom.apply, _.value)
