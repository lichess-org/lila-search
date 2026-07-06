package lila.search
package forum2

object Mapping:
  def fields = MappingGenerator.generateFields(es.Forum2Source.schema)
