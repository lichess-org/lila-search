package lila.search
package ingestor

import weaver.*

import java.time.Instant

object TranslateForum2Test extends FunSuite:

  private def post(lang: Option[String]) = DbForum(
    post = DbPost(
      id = "p1",
      text = "Les chevaux blancs",
      topicId = "t1",
      troll = false,
      createdAt = Instant.EPOCH,
      userId = Some("pierre"),
      erasedAt = None,
      lang = lang
    ),
    topicName = "Ouvertures"
  )

  test("supported language routes body and topic into its key"):
    val src = Translate.forum2(post(Some("fr")))
    expect.all(
      src.bodyByLang == Some(Map("fr" -> "Les chevaux blancs")),
      src.topicByLang == Some(Map("fr" -> "Ouvertures")),
      src.language == Some("fr")
    )

  test("unsupported language keeps only the fallback fields"):
    val src = Translate.forum2(post(Some("tlh")))
    expect.all(src.bodyByLang == None, src.topicByLang == None, src.language == Some("tlh"))

  test("absent language keeps only the fallback fields"):
    val src = Translate.forum2(post(None))
    expect.all(src.bodyByLang == None, src.topicByLang == None, src.language == None)
