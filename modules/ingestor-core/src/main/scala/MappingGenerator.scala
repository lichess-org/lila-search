package lila.search

import smithy4s.Hints
import smithy4s.schema.Schema
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.fields.*

/** Generates Elasticsearch field mappings from Smithy schemas annotated with ES traits */
object MappingGenerator:

  /** Generate field mappings from a Smithy schema */
  def generateFields[A](schema: Schema[A]): List[ElasticField] =
    schema match
      case Schema.StructSchema(fields = fields) =>
        fields.toList.flatMap { field =>
          // Use jsonName if present, otherwise use the Smithy field label
          val fieldName = field.hints.get[smithy.api.JsonName].map(_.value).getOrElse(field.label)
          generateField(fieldName, field.hints)
        }
      case _ => Nil

  private def generateTextField(fieldName: String, traitData: es.TextField): ElasticField =
    val baseField = textField(fieldName)
      .copy(
        boost = traitData.boost.map(_.toDouble),
        analyzer = traitData.analyzer
      )
    // If keyword subfield is specified, add it
    traitData.keywordSubfield match
      case Some(kw) =>
        baseField.copy(fields = List(keywordField(kw.name).copy(normalizer = kw.normalizer)))
      case None => baseField

  private def generateKeywordField(fieldName: String, traitData: es.KeywordField): ElasticField =
    keywordField(fieldName).copy(
      boost = traitData.boost.map(_.toDouble),
      docValues = Some(traitData.docValues)
    )

  private def generateDateField(fieldName: String, traitData: es.DateField): ElasticField =
    dateField(fieldName).copy(
      format = traitData.format,
      docValues = traitData.docValues
    )

  private def generateShortField(fieldName: String, traitData: es.ShortField): ElasticField =
    shortField(fieldName).copy(docValues = traitData.docValues)

  private def generateIntField(fieldName: String, traitData: es.IntField): ElasticField =
    intField(fieldName).copy(docValues = traitData.docValues)

  private def generateBooleanField(fieldName: String, traitData: es.BooleanField): ElasticField =
    booleanField(fieldName).copy(docValues = traitData.docValues)

  private def generateField(fieldName: String, hints: Hints): Option[ElasticField] =
    import lila.search.es.*

    // Check for ES field type traits using implicit ShapeTag
    hints
      .get(using TextField)
      .map(generateTextField(fieldName, _))
      .orElse(hints.get(using KeywordField).map(generateKeywordField(fieldName, _)))
      .orElse(hints.get(using DateField).map(generateDateField(fieldName, _)))
      .orElse(hints.get(using ShortField).map(generateShortField(fieldName, _)))
      .orElse(hints.get(using IntField).map(generateIntField(fieldName, _)))
      .orElse(hints.get(using BooleanField).map(generateBooleanField(fieldName, _)))
