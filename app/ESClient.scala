package lila.search

import scala.concurrent.Future

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.{ TypedFieldDefinition }
import com.sksamuel.elastic4s.{ ElasticDsl, ElasticClient }
import play.api.libs.json._

final class ESClient(client: ElasticClient) {

  def search(index: Index, query: JsObject, from: From, size: Size) = client execute {
    ElasticDsl.search in index.withType rawQuery Json.stringify(query) from from.value size size.value
  } map SearchResponse.apply

  def count(index: Index, query: JsObject) = client execute {
    ElasticDsl.count from index.withType rawQuery Json.stringify(query)
  } map CountResponse.apply

  def store(index: Index, id: Id, obj: JsObject) = client execute {
    ElasticDsl.index into index.withType source Json.stringify(obj) id id.value
  }

  def deleteById(index: Index, id: Id) = client execute {
    ElasticDsl.delete id id.value from index.withType
  }

  def deleteByQuery(index: Index, query: Query) = client execute {
    ElasticDsl.delete from index.withType where query.value
  }

  def putMapping(index: Index, fields: Seq[TypedFieldDefinition]) = client execute {
    ElasticDsl.put mapping index.indexType as fields
  }

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
