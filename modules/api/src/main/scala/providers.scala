package lila.search
package spec

import cats.syntax.all.*
import smithy4s.*

object providers:

  given RefinementProvider[DateTimeFormat, String, SearchDateTime] =
    Refinement.drivenBy(SearchDateTime.fromString, _.value)

  given RefinementProvider[SizeFormat, Int, SearchSize] =
    Refinement.drivenBy(x => SearchSize(x).asRight, _.value)

  given RefinementProvider[FromFormat, Int, SearchFrom] =
    Refinement.drivenBy(x => SearchFrom(x).asRight, _.value)

  given RefinementProvider[IndexFormat, String, Index] =
    Refinement.drivenBy(Index.fromString, _.value)
