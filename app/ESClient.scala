package lila.search

import scala.concurrent.{ Future, ExecutionContext }

import com.sksamuel.elastic4s.http.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.http.{ ElasticDsl, HttpClient, RequestFailure, RequestSuccess }
import com.sksamuel.elastic4s.mappings.FieldDefinition
import play.api.libs.json._

final class ESClient(client: HttpClient)(implicit ec: ExecutionContext) {

  private var writeable = true

  private def Write[A](f: => Fu[A]): Funit =
    if (writeable) f.void
    else funit

  private def toResult[A](response: Either[RequestFailure, RequestSuccess[A]]): Future[A] =
    response.fold(
      fail => Future.failed(new Exception(fail.error.reason)),
      suc => Future.successful(suc.result)
    )

  def search(index: Index, query: Query, from: From, size: Size) = client execute {
    query.searchDef(from, size)(index)
  } flatMap toResult map SearchResponse.apply

  def count(index: Index, query: Query) = client execute {
    query.countDef(index)
  } flatMap toResult map CountResponse.apply

  def store(index: Index, id: Id, obj: JsObject) = Write {
    client execute {
      indexInto(index.toString) source Json.stringify(obj) id id.value
    }
  }

  def storeBulk(index: Index, objs: JsObject) =
    if (objs.fields.isEmpty) funit
    else client execute {
      ElasticDsl.bulk {
        objs.fields.collect {
          case (id, JsString(doc)) =>
            indexInto(index.toString) source doc id id
        }
      }
    }

  def deleteById(index: Index, id: Id) = Write {
    client execute {
      delete(id.value) from index.toString
    }
  }

  def deleteByIds(index: Index, ids: List[Id]) = Write {
    client execute {
      ElasticDsl.bulk {
        ids.map { id =>
          delete(id.value) from index.toString
        }
      }
    }
  }

  def putMapping(index: Index, fields: Seq[FieldDefinition]) =
    dropIndex(index) >> client.execute {
      createIndex(index.name) mappings (
        mapping(index.name) fields fields source false all false
      ) shards 1 replicas 0 refreshInterval Which.refreshInterval(index)
    }

  def refreshIndex(index: Index) =
    client.execute {
      ElasticDsl refreshIndex index.name
    }.recover {
      case _: Exception =>
        println(s"Failed to refresh index $index")
    }

  private def dropIndex(index: Index) =
    client.execute {
      deleteIndex(index.name)
    }.recover {
      case _: Exception =>
    }
}
