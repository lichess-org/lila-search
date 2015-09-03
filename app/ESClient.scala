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
    ElasticDsl.index into index.toString source Json.stringify(obj) id id.value
  }

  def storeBulk(index: Index, objs: JsObject) = client execute {
    ElasticDsl.bulk {
      objs.fields.collect {
        case (id, JsString(doc)) =>
          ElasticDsl.index into index.toString source doc id id
      }
    }
  }

  def deleteById(index: Index, id: Id) = client execute {
    ElasticDsl.delete id id.value from index.toString
  }

  def deleteByQuery(index: Index, query: StringQuery) = client execute {
    ElasticDsl.delete from index.toString where query.value
  }

  def putMapping(index: Index, fields: Seq[TypedFieldDefinition]) =
    resetIndex(index) >>
      client.execute {
        ElasticDsl.put mapping index.indexType as fields
      }

  def aliasTo(tempIndex: Index, mainIndex: Index) =
    deleteIndex(mainIndex) >> client.execute {
      add alias mainIndex.name on tempIndex.name
    }

  private def resetIndex(index: Index) =
    deleteIndex(index) >> client.execute {
      ElasticDsl.create index index.name
    }

  private def deleteIndex(index: Index) =
    client.execute {
      ElasticDsl.delete index index.name
    }.recover {
      case _: Exception =>
    }
}
