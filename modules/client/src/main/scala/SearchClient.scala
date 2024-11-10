package lila.search
package client

import lila.search.spec.*
import play.api.libs.ws.StandaloneWSClient

import scala.concurrent.{ ExecutionContext, Future }

trait SearchClient extends SearchService[Future]

object SearchClient:

  def noop: SearchClient = NoopSearchClient

  def play(client: StandaloneWSClient, baseUrl: String)(using ec: ExecutionContext): SearchClient =
    PlaySearchClient(client, baseUrl)
