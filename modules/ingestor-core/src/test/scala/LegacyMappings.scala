package lila.search
package ingestor

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.fields.ElasticField

/** Legacy manual mapping definitions preserved for testing purposes */
object LegacyMappings:

  object forum:
    object Fields:
      val body = "bo"
      val topic = "to"
      val topicId = "ti"
      val author = "au"
      val troll = "tr"
      val date = "da"

    def fields: Seq[ElasticField] =
      import Fields.*
      Seq(
        textField(body).copy(boost = Some(2), analyzer = Some("english")),
        textField(topic).copy(boost = Some(5), analyzer = Some("english")),
        keywordField(author).copy(docValues = Some(false)),
        keywordField(topicId).copy(docValues = Some(false)),
        booleanField(troll).copy(docValues = Some(false)),
        dateField(date)
      )

  object game:
    object Fields:
      val status = "s"
      val turns = "t"
      val rated = "r"
      val perf = "p"
      val uids = "u"
      val winner = "w"
      val loser = "o"
      val winnerColor = "c"
      val averageRating = "a"
      val ai = "i"
      val date = "d"
      val duration = "l"
      val clockInit = "ct"
      val clockInc = "ci"
      val analysed = "n"
      val whiteUser = "wu"
      val blackUser = "bu"
      val source = "so"

    def fields: Seq[ElasticField] =
      import Fields.*
      Seq( // only keep docValues for sortable fields
        keywordField(status).copy(docValues = Some(false)),
        shortField(turns).copy(docValues = Some(true)),
        booleanField(rated).copy(docValues = Some(false)),
        keywordField(perf).copy(docValues = Some(false)),
        keywordField(uids).copy(docValues = Some(false)),
        keywordField(winner).copy(docValues = Some(false)),
        keywordField(loser).copy(docValues = Some(false)),
        keywordField(winnerColor).copy(docValues = Some(false)),
        shortField(averageRating).copy(docValues = Some(true)),
        shortField(ai).copy(docValues = Some(false)),
        dateField(date).copy(format = Some(SearchDateTime.format), docValues = Some(true)),
        intField(duration).copy(docValues = Some(false)),
        intField(clockInit).copy(docValues = Some(false)),
        shortField(clockInc).copy(docValues = Some(false)),
        booleanField(analysed).copy(docValues = Some(false)),
        keywordField(whiteUser).copy(docValues = Some(false)),
        keywordField(blackUser).copy(docValues = Some(false)),
        keywordField(source).copy(docValues = Some(false))
      )

  object study:
    object Fields:
      val name = "name"
      val nameRaw = "raw"
      val owner = "owner"
      val members = "members"
      val chapterNames = "chapterNames"
      val chapterTexts = "chapterTexts"
      val topics = "topics"
      val createdAt = "createdAt_date"
      val updatedAt = "updatedAt_date"
      val rank = "rank"
      val likes = "likes"
      val public = "public"

    def fields: Seq[ElasticField] =
      import Fields.*
      Seq(
        textField(name)
          .copy(boost = Some(10), analyzer = Some("english"))
          .copy(fields = List(keywordField(nameRaw))),
        keywordField(owner).copy(boost = Some(2), docValues = Some(false)),
        keywordField(members).copy(boost = Some(1), docValues = Some(false)),
        textField(chapterNames).copy(boost = Some(4), analyzer = Some("english")),
        textField(chapterTexts).copy(boost = Some(1), analyzer = Some("english")),
        textField(topics).copy(boost = Some(5), analyzer = Some("english")),
        shortField(likes).copy(docValues = Some(true)), // sort by likes
        booleanField(public).copy(docValues = Some(false)),
        dateField(rank).copy(format = Some(SearchDateTime.format)),
        dateField(createdAt).copy(format = Some(SearchDateTime.format)),
        dateField(updatedAt).copy(format = Some(SearchDateTime.format))
      )

  object team:
    object Fields:
      val name = "na"
      val description = "de"
      val nbMembers = "nbm"

    def fields: Seq[ElasticField] =
      import Fields.*
      Seq(
        textField(name).copy(boost = Some(10), analyzer = Some("english")),
        textField(description).copy(boost = Some(2), analyzer = Some("english")),
        shortField(nbMembers)
      )

  object ublog:
    object Fields:
      val text = "text"
      val likes = "likes"
      val quality = "quality"
      val language = "language"
      val date = "date"

    def fields: Seq[ElasticField] =
      import Fields.*
      Seq(
        textField(text),
        shortField(quality).copy(docValues = Some(true)),
        keywordField(language).copy(docValues = Some(false)),
        shortField(likes).copy(docValues = Some(true)),
        dateField(date).copy(docValues = Some(true))
      )
