package lila.search
package ingestor

import cats.Show
import cats.effect.IO
import cats.syntax.all.*
import org.scalacheck.{ Arbitrary, Gen }
import weaver.*
import weaver.scalacheck.*

object HasDocIdTest extends SimpleIOSuite with Checkers:

  case class Change(value: Int, docId: Option[String])
  given HasDocId[Change] with
    extension (a: Change) def docId: Option[String] = a.docId

  given Show[Change] = Show.fromToString
  given Arbitrary[Change] = Arbitrary:
    for
      value <- Gen.posNum[Int]
      docId <- Gen.option(Gen.alphaNumStr)
    yield Change(value, docId)

  test("distincByDocId is empty when input is empty"):
    val changes = List.empty[Change]
    val result  = changes.distincByDocId
    IO(expect(List.empty[Option[String]] == result))

  test("distincByDocId is empty when all docIds are none"):
    forall: (changes: List[Change]) =>
      val xs = changes.map(_.copy(docId = none))
      expect(xs.distincByDocId.isEmpty)

  test("distincByDocId contains only item with defined docId"):
    forall: (changes: List[Change]) =>
      expect(changes.distincByDocId.forall(_.docId.isDefined))

  test("distincByDocId is idempotent"):
    forall: (changes: List[Change]) =>
      expect(changes.distincByDocId == changes.distincByDocId.distincByDocId)

  test("distincByDocId == reverse.distincBy.reverse"):
    forall: (changes: List[Change]) =>
      val result         = changes.distincByDocId
      val doubleReversed = changes.reverse.filter(_.docId.isDefined).distinctBy(_.docId).reverse
      expect(result == doubleReversed)
