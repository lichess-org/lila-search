package lila.search
package ingestor

import weaver.*
import com.sksamuel.elastic4s.fields.*

/** Tests to verify that generated mappings match the manually defined mappings */
object MappingComparisonSuite extends SimpleIOSuite:

  // Helper to compare field sequences
  def compareFields(
      generated: Seq[ElasticField],
      manual: Seq[ElasticField],
      indexName: String
  ): Expectations =
    val genMap = generated.map(f => f.name -> f).toMap
    val manMap = manual.map(f => f.name -> f).toMap

    val allFieldNames = (genMap.keySet ++ manMap.keySet).toSeq.sorted

    allFieldNames.foldLeft(success) { case (acc, fieldName) =>
      (genMap.get(fieldName), manMap.get(fieldName)) match
        case (Some(gen), Some(man)) =>
          acc and compareField(gen, man, indexName, fieldName)
        case (None, Some(_)) =>
          acc and failure(s"[$indexName] Field '$fieldName' missing in generated mappings")
        case (Some(_), None) =>
          acc and failure(s"[$indexName] Field '$fieldName' missing in manual mappings")
        case (None, None) =>
          acc // Shouldn't happen
    }

  // Helper to compare individual fields
  def compareField(gen: ElasticField, man: ElasticField, indexName: String, fieldName: String): Expectations =
    (gen, man) match
      case (g: TextField, m: TextField) =>
        val checks = List(
          (g.name == m.name, s"name: ${g.name} vs ${m.name}"),
          (g.boost == m.boost, s"boost: ${g.boost} vs ${m.boost}"),
          (g.analyzer == m.analyzer, s"analyzer: ${g.analyzer} vs ${m.analyzer}"),
          (
            g.fields.map(_.name).sorted == m.fields.map(_.name).sorted,
            s"subfields: ${g.fields.map(_.name)} vs ${m.fields.map(_.name)}"
          )
        )
        checks.foldLeft(success) { case (acc, (check, msg)) =>
          acc and (if check then success else failure(s"[$indexName.$fieldName] TextField $msg"))
        }

      case (g: KeywordField, m: KeywordField) =>
        val checks = List(
          (g.name == m.name, s"name: ${g.name} vs ${m.name}"),
          (g.boost == m.boost, s"boost: ${g.boost} vs ${m.boost}"),
          (g.docValues == m.docValues, s"docValues: ${g.docValues} vs ${m.docValues}")
        )
        checks.foldLeft(success) { case (acc, (check, msg)) =>
          acc and (if check then success else failure(s"[$indexName.$fieldName] KeywordField $msg"))
        }

      case (g: DateField, m: DateField) =>
        val checks = List(
          (g.name == m.name, s"name: ${g.name} vs ${m.name}"),
          (g.format == m.format, s"format: ${g.format} vs ${m.format}"),
          (g.docValues == m.docValues, s"docValues: ${g.docValues} vs ${m.docValues}")
        )
        checks.foldLeft(success) { case (acc, (check, msg)) =>
          acc and (if check then success else failure(s"[$indexName.$fieldName] DateField $msg"))
        }

      case (g: ShortField, m: ShortField) =>
        val checks = List(
          (g.name == m.name, s"name: ${g.name} vs ${m.name}"),
          (g.docValues == m.docValues, s"docValues: ${g.docValues} vs ${m.docValues}")
        )
        checks.foldLeft(success) { case (acc, (check, msg)) =>
          acc and (if check then success else failure(s"[$indexName.$fieldName] ShortField $msg"))
        }

      case (g: IntegerField, m: IntegerField) =>
        val checks = List(
          (g.name == m.name, s"name: ${g.name} vs ${m.name}"),
          (g.docValues == m.docValues, s"docValues: ${g.docValues} vs ${m.docValues}")
        )
        checks.foldLeft(success) { case (acc, (check, msg)) =>
          acc and (if check then success else failure(s"[$indexName.$fieldName] IntegerField $msg"))
        }

      case (g: BooleanField, m: BooleanField) =>
        val checks = List(
          (g.name == m.name, s"name: ${g.name} vs ${m.name}"),
          (g.docValues == m.docValues, s"docValues: ${g.docValues} vs ${m.docValues}")
        )
        checks.foldLeft(success) { case (acc, (check, msg)) =>
          acc and (if check then success else failure(s"[$indexName.$fieldName] BooleanField $msg"))
        }

      case _ =>
        failure(
          s"[$indexName.$fieldName] Type mismatch: gen=${gen.getClass.getSimpleName}, manual=${man.getClass.getSimpleName}"
        )

  pureTest("Study index: generated mappings match manual mappings"):
    val generated = GeneratedMappings.study.fields
    val manual = study.Mapping.fields
    compareFields(generated, manual, "study") &&
    expect(generated.toSet == manual.toSet)

  pureTest("Game index: generated mappings match manual mappings"):
    val generated = GeneratedMappings.game.fields
    val manual = game.Mapping.fields
    compareFields(generated, manual, "game") &&
    expect(generated.toSet == manual.toSet)

  pureTest("Forum index: generated mappings match manual mappings"):
    val generated = GeneratedMappings.forum.fields
    val manual = forum.Mapping.fields
    compareFields(generated, manual, "forum") &&
    expect(generated.toSet == manual.toSet)

  pureTest("Team index: generated mappings match manual mappings"):
    val generated = GeneratedMappings.team.fields
    val manual = team.Mapping.fields
    compareFields(generated, manual, "team") &&
    expect(generated.toSet == manual.toSet)

  pureTest("Ublog index: generated mappings match manual mappings"):
    val generated = GeneratedMappings.ublog.fields
    val manual = ublog.Mapping.fields
    compareFields(generated, manual, "ublog") &&
    expect(generated.toSet == manual.toSet)
