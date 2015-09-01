package lila.search

import scala.concurrent.Future

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.{ TypedFieldDefinition }
import com.sksamuel.elastic4s.{ ElasticDsl, ElasticClient }
import play.api.libs.json._

final class ESClient(client: ElasticClient) {

  def search(index: Index, query: Query, from: From, size: Size) = client execute {
    query.searchDef(from, size)(index)
  } map SearchResponse.apply

  def count(index: Index, query: Query) = client execute {
    query.countDef(index)
  } map CountResponse.apply

  def store(index: Index, id: Id, obj: JsObject) = client execute {
    ElasticDsl.index into index.withType source Json.stringify(obj) id id.value
  }

  def storeBulk(index: Index, objs: JsObject) = client execute {
    ElasticDsl.bulk {
      objs.fields.collect {
        case (id, obj: JsObject) =>
          ElasticDsl.index into index.withType source Json.stringify(obj) id id
      }
    }
  }

  def deleteById(index: Index, id: Id) = client execute {
    ElasticDsl.delete id id.value from index.withType
  }

  def deleteByQuery(index: Index, query: StringQuery) = client execute {
    ElasticDsl.delete from index.withType where query.value
  }

  def putMapping(index: Index, fields: Seq[TypedFieldDefinition]) =
    resetIndex(index) >>
      client.execute {
        ElasticDsl.put mapping index.indexType as fields
      }

  private def resetIndex(index: Index) =
    client.execute {
      ElasticDsl.delete index index.name
    }.recover {
      case _: Exception =>
    } >>
      client.execute {
        ElasticDsl.create index index.name
      }
}
