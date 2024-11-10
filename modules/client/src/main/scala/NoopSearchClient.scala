package lila.search
package client

import lila.search.spec.*

import scala.concurrent.Future

object NoopSearchClient extends SearchClient:

  override def search(query: Query, from: From, size: Size): Future[SearchOutput] =
    Future.successful(SearchOutput(Nil))

  override def count(query: Query): Future[CountOutput] = Future.successful(CountOutput(0))
