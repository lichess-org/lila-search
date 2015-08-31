package lila.search

import scala.concurrent.Future

import com.sksamuel.elastic4s.Executable
import com.sksamuel.elastic4s.mappings.{ PutMappingDefinition }
import com.sksamuel.elastic4s.{ ElasticClient, CountDefinition, SearchDefinition, IndexDefinition, BulkDefinition }
import com.sksamuel.elastic4s.ElasticDsl
import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import play.api.libs.json._

final class ESClient(client: ElasticClient) {

  def search(index: Index, query: JsObject, from: From, size: Size) = client execute {
    ElasticDsl.search in index.withType rawQuery Json.stringify(query) from from.value size size.value
  } map SearchResponse.apply

  def count(index: Index, query: JsObject) = client execute {
    ElasticDsl.count from index.withType rawQuery Json.stringify(query)
  } map CountResponse.apply

  def store(index: Index, id: Id, obj: JsObject) = client execute {
    val fields = obj.fields.collect {
      case (k, JsString(str)) => k -> str
      case (k, JsNumber(nb))  => k -> nb
    }
    ElasticDsl.index into index.withType fields fields id id.value
  }

  def deleteById(index: Index, id: Id) = client execute {
    ElasticDsl.delete id id.value from index.withType
  }

  def deleteByQuery(index: Index, query: Query) = client execute {
    ElasticDsl.delete from index.withType where query.value
  }
  // def bulk(d: BulkDefinition) = client execute d void

  // def put(d: PutMappingDefinition) = client execute d void

  // def createType(indexName: String, typeName: String) {
  //   try {
  //     import scala.concurrent.Await
  //     import scala.concurrent.duration._
  //     Await.result(client execute {
  //       create index indexName
  //     }, 10.seconds)
  //   }
  //   catch {
  //     case e: Exception => // println("create type: " + e)
  //   }
  //   // client.sync execute {
  //   //   delete from indexName -> typeName where matchall
  //   // }
  //   import org.elasticsearch.index.query.QueryBuilders._
  //   client.java.prepareDeleteByQuery(indexName)
  //     .setTypes(typeName)
  //     .setQuery(matchAllQuery)
  //     .execute()
  //     .actionGet()
  // }
}
