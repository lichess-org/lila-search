package lila.search
package ingestor

trait HasDocId[A]:
  extension (a: A) def docId: Option[String]
  extension (xs: List[A])
    /**
     * Returns a list of distinct changes by their document id in the reverse order they appear in the input
     * list. If a change has no document id, We ignore it.
     */
    def unique: List[A] =
      xs
        .foldRight(List.empty[A] -> Set.empty) { case (change, p @ (acc, ids)) =>
          if change.docId.exists(!ids.contains(_))
          then (change :: acc) -> (ids + id)
          else p
        }
        ._1
