package lila.search

import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.fields.ElasticField

/**
 * Legacy manual mapping definitions preserved for testing purposes
 */
object LegacyMappings:

  object forum:
    def fields: Seq[ElasticField] =
      import lila.search.forum.Fields.*
      Seq(
        textField(body).copy(boost = Some(2), analyzer = Some("english")),
        textField(topic).copy(boost = Some(5), analyzer = Some("english")),
        keywordField(author).copy(docValues = Some(false)),
        keywordField(topicId).copy(docValues = Some(false)),
        booleanField(troll).copy(docValues = Some(false)),
        dateField(date)
      )

  object game:
    def fields: Seq[ElasticField] =
      import lila.search.game.Fields.*
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
    def fields: Seq[ElasticField] =
      import lila.search.study.Fields.*
      Seq(
        textField(name)
          .copy(analyzer = Some("english"), searchAnalyzer = Some("english_with_chess_synonyms"))
          .copy(fields = List(keywordField(nameRaw).copy(normalizer = Some("lowercase")))),
        textField(description)
          .copy(analyzer = Some("english"), searchAnalyzer = Some("english_with_chess_synonyms")),
        keywordField(owner).copy(docValues = Some(false)),
        keywordField(members).copy(docValues = Some(false)),
        textField(topics)
          .copy(analyzer = Some("english"), searchAnalyzer = Some("english_with_chess_synonyms")),
        intField(likes),
        booleanField(public),
        dateField(rank).copy(format = Some(SearchDateTime.format)),
        dateField(createdAt).copy(format = Some(SearchDateTime.format)),
        dateField(updatedAt).copy(format = Some(SearchDateTime.format))
      )

  object team:
    def fields: Seq[ElasticField] =
      import lila.search.team.Fields.*
      Seq(
        textField(name).copy(boost = Some(10), analyzer = Some("english")),
        textField(description).copy(boost = Some(2), analyzer = Some("english")),
        shortField(nbMembers)
      )

  object ublog:
    def fields: Seq[ElasticField] =
      import lila.search.ublog.Fields.*
      Seq(
        textField(text),
        shortField(quality).copy(docValues = Some(true)),
        keywordField(language).copy(docValues = Some(false)),
        shortField(likes).copy(docValues = Some(true)),
        dateField(date).copy(docValues = Some(true))
      )
