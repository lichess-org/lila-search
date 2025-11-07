package lila.search
package ingestor

trait HasDocId[A]:
  extension (a: A) def docId: Option[String]
  extension (xs: List[A])
    /**
     * Returns a list of distinct changes by their document id in the reverse order they appear in the input
     * list. If a change has no document id, We ignore it.
     */
    def distincByDocId: List[A] =
      xs
        .foldRight(List.empty[A] -> Set.empty[String]) { case (change, p @ (acc, ids)) =>
          change.docId.fold(p) { id =>
            ids.contains(id).fold(p, (change :: acc) -> (ids + id))
          }
        }
        ._1
