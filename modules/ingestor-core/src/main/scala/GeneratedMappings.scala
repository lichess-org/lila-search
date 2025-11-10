package lila.search
package ingestor

import com.sksamuel.elastic4s.fields.ElasticField

/** Generated Elasticsearch mappings from Smithy schemas */
object GeneratedMappings:

  object study:
    def fields: Seq[ElasticField] = MappingGenerator.generateFields(StudySource.schema)

  object game:
    def fields: Seq[ElasticField] = MappingGenerator.generateFields(GameSource.schema)

  object forum:
    def fields: Seq[ElasticField] = MappingGenerator.generateFields(ForumSource.schema)

  object team:
    def fields: Seq[ElasticField] = MappingGenerator.generateFields(TeamSource.schema)

  object ublog:
    def fields: Seq[ElasticField] = MappingGenerator.generateFields(UblogSource.schema)
