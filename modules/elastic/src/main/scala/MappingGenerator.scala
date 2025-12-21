package lila.search

import cats.syntax.all.*
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.fields.*
import smithy4s.Hints
import smithy4s.schema.Schema

/**
 * Generates Elasticsearch field mappings from Smithy schemas annotated with ES traits
 */
object MappingGenerator:

  /**
   * Generate field mappings from a Smithy schema
   */
  def generateFields[A](schema: Schema[A]): List[ElasticField] =
    schema match
      case Schema.StructSchema(fields = fields) =>
        fields.toList.flatMap { field =>
          // Use jsonName if present, otherwise use the Smithy field label
          val fieldName = field.hints.get[smithy.api.JsonName].map(_.value).getOrElse(field.label)
          generateField(fieldName, field.hints, field.schema)
        }
      case Schema.OptionSchema(underlying) =>
        // Unwrap optional schemas and process the underlying structure
        generateFields(underlying)
      case _ => Nil

  private def generateTextField(fieldName: String, hints: Hints): Option[ElasticField] =
    hints
      .get[es.TextField]
      .map: traitData =>
        val baseField = textField(fieldName)
          .copy(
            boost = traitData.boost.map(_.toDouble),
            analyzer = traitData.analyzer,
            searchAnalyzer = traitData.searchAnalyzer
          )
        // If keyword subfield is specified, add it
        traitData.keywordSubfield match
          case Some(kw) =>
            baseField.copy(fields = List(keywordField(kw.name).copy(normalizer = kw.normalizer)))
          case None => baseField

  private def generateKeywordField(fieldName: String, hints: Hints): Option[ElasticField] =
    hints
      .get[es.KeywordField]
      .map: traitData =>
        keywordField(fieldName).copy(
          boost = traitData.boost.map(_.toDouble),
          docValues = traitData.docValues
        )

  private def generateDateField(fieldName: String, hints: Hints): Option[ElasticField] =
    hints
      .get[es.DateField]
      .map: traitData =>
        dateField(fieldName).copy(
          format = traitData.format,
          docValues = traitData.docValues
        )

  private def generateShortField(fieldName: String, hints: Hints): Option[ElasticField] =
    hints
      .get[es.ShortField]
      .map: traitData =>
        shortField(fieldName).copy(docValues = traitData.docValues)

  private def generateIntField(fieldName: String, hints: Hints): Option[ElasticField] =
    hints
      .get[es.IntField]
      .map: traitData =>
        intField(fieldName).copy(docValues = traitData.docValues)

  private def generateBooleanField(fieldName: String, hints: Hints): Option[ElasticField] =
    hints
      .get[es.BooleanField]
      .map: traitData =>
        booleanField(fieldName).copy(docValues = traitData.docValues)

  private def generateNestedField(fieldName: String, hints: Hints, fieldSchema: Schema[?]): Option[ElasticField] =
    hints
      .get[es.NestedField]
      .map: traitData =>
        val nestedFields = generateFields(fieldSchema)
        nestedField(fieldName).copy(
          dynamic = traitData.dynamic,
          includeInParent = traitData.includeInParent,
          properties = nestedFields
        )

  private def generateField(fieldName: String, hints: Hints, schema: Schema[?]): Option[ElasticField] =
    generateTextField(fieldName, hints) <+>
      generateKeywordField(fieldName, hints) <+>
      generateDateField(fieldName, hints) <+>
      generateShortField(fieldName, hints) <+>
      generateIntField(fieldName, hints) <+>
      generateBooleanField(fieldName, hints) <+>
      generateNestedField(fieldName, hints, schema)
