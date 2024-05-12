package lila.search
package client

import lila.search.spec.*
import play.api.libs.ws.StandaloneWSClient
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.annotation.targetName

trait SearchClient extends SearchService[Future] { client =>
  @targetName("storeBulkTeamWithPair")
  def storeBulkTeam(sources: List[(String, TeamSource)]): Future[Unit] =
    client.storeBulkTeam(sources.map(TeamSourceWithId.apply.tupled))

  @targetName("storeBulkStudyWithPair")
  def storeBulkStudy(sources: List[(String, StudySource)]): Future[Unit] =
    client.storeBulkStudy(sources.map(StudySourceWithId.apply.tupled))

  @targetName("storeBulkGameWithPair")
  def storeBulkGame(sources: List[(String, GameSource)]): Future[Unit] =
    client.storeBulkGame(sources.map(GameSourceWithId.apply.tupled))

  @targetName("storeBulkForumWithPair")
  def storeBulkForum(sources: List[(String, ForumSource)]): Future[Unit] =
    client.storeBulkForum(sources.map(ForumSourceWithId.apply.tupled))

  def storeForum(id: String, source: ForumSource): Future[Unit] =
    client.store(id, Source.forum(source))

  def storeGame(id: String, source: GameSource): Future[Unit] =
    client.store(id, Source.game(source))

  def storeStudy(id: String, source: StudySource): Future[Unit] =
    client.store(id, Source.study(source))

  def storeTeam(id: String, source: TeamSource): Future[Unit] =
    client.store(id, Source.team(source))
}

object SearchClient:

  def noop: SearchClient = NoopSearchClient

  def play(client: StandaloneWSClient, baseUrl: String)(using ec: ExecutionContext): SearchClient =
    PlaySearchClient(client, baseUrl)
