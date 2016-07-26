package lila.search

import scala.concurrent.Future

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.{ TypedFieldDefinition }
import com.sksamuel.elastic4s.{ ElasticDsl, ElasticClient }
import play.api.libs.json._

final class ESClient(client: ElasticClient) {

  private var writeable = true

  private def Write[A](f: => Fu[A]): Funit =
    if (writeable) f.void
    else funit

  def search(index: Index, query: Query, from: From, size: Size) = client execute {
    query.searchDef(from, size)(index)
  } map SearchResponse.apply

  def count(index: Index, query: Query) = client execute {
    query.countDef(index)
  } map CountResponse.apply

  def store(index: Index, id: Id, obj: JsObject) = Write {
    client execute {
      ElasticDsl.index into index.toString source Json.stringify(obj) id id.value
    }
  }

  def storeBulk(index: Index, objs: JsObject) =
    if (objs.fields.isEmpty) funit
    else client execute {
      ElasticDsl.bulk {
        objs.fields.collect {
          case (id, JsString(doc)) =>
            ElasticDsl.index into index.toString source doc id id
        }
      }
    }

  def deleteById(index: Index, id: Id) = Write {
    client execute {
      ElasticDsl.delete id id.value from index.toString
    }
  }

  def deleteByIds(index: Index, ids: List[Id]) = Write {
    client execute {
      ElasticDsl.bulk {
        ids.map { id =>
          ElasticDsl.delete id id.value from index.toString
        }
      }
    }
  }

  def putMapping(index: Index, fields: Seq[TypedFieldDefinition]) =
    deleteIndex(index) >> client.execute {
      ElasticDsl.create index index.name mappings (
        mapping(index.name) fields fields source false all false
      ) shards 1 replicas 0 refreshInterval Which.refreshInterval(index)
    }

  private def deleteIndex(index: Index) =
    client.execute {
      ElasticDsl.delete index index.name
    }.recover {
      case _: Exception =>
    }
}
