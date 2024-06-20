package lila.search
package spec

import cats.syntax.all.*
import smithy4s.*

object providers:

  given RefinementProvider[DateTimeFormat, String, SearchDateTime] =
    Refinement.drivenBy(SearchDateTime.fromString, _.value)

  given RefinementProvider[SizeFormat, Int, Size] =
    Refinement.drivenBy(x => Size(x).asRight, _.value)

  given RefinementProvider[FromFormat, Int, From] =
    Refinement.drivenBy(x => From(x).asRight, _.value)

  given RefinementProvider[IndexFormat, String, Index] =
    Refinement.drivenBy(Index.fromString, _.value)
