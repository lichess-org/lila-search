package lila.search
package client

import lila.search.spec.*

import scala.concurrent.Future

object NoopSearchClient extends SearchClient:

  override def refresh(index: Index): Future[Unit] = Future.successful(())

  override def storeBulkTeam(sources: List[TeamSourceWithId]): Future[Unit] = Future.successful(())

  override def deleteByIds(index: Index, ids: List[String]): Future[Unit] = Future.successful(())

  override def storeBulkForum(sources: List[ForumSourceWithId]): Future[Unit] = Future.successful(())

  override def deleteById(index: Index, id: String): Future[Unit] = Future.successful(())

  override def search(query: Query, from: From, size: Size): Future[SearchOutput] =
    Future.successful(SearchOutput(Nil))

  override def store(id: String, source: Source): Future[Unit] = Future.successful(())

  override def storeBulkGame(sources: List[GameSourceWithId]): Future[Unit] = Future.successful(())

  override def storeBulkStudy(sources: List[StudySourceWithId]): Future[Unit] = Future.successful(())

  override def count(query: Query): Future[CountOutput] = Future.successful(CountOutput(0))

  override def mapping(index: Index): Future[Unit] = Future.successful(())
