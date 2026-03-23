package lila.search
package ingestor.game

import cats.effect.IO
import cats.mtl.Handle.*
import cats.syntax.all.*
import lila.search.ingestor.{ BotUserCache, DbGame, Ingestor, Repo, gameIndexable, given }
import org.typelevel.log4cats.{ Logger, LoggerFactory }

class ESGameIngestor(elastic: ESClient[IO], botCache: BotUserCache)(using LoggerFactory[IO])
    extends Ingestor[DbGame]:

  private given Logger[IO] = LoggerFactory[IO].getLoggerFromName("game.ingestor")

  def ingest(stream: fs2.Stream[IO, Repo.Result[DbGame]]): IO[Unit] =
    stream
      .evalMap: result =>
        botCache.get.flatMap: botIds =>
          given indexable: com.sksamuel.elastic4s.Indexable[DbGame] = gameIndexable(botIds)
          Logger[IO].info(s"Indexing ${result.toIndex.size} docs to game") *>
            allow(elastic.storeBulk(Index.Game, result.toIndex))
              .rescue: e =>
                Logger[IO].error(e.asException)(
                  s"Failed to game index: ${result.toIndex.map(_.id).mkString(", ")}"
                ) *> IO.raiseError(e.asException)
              .whenA(result.toIndex.nonEmpty) *>
            allow(elastic.deleteMany(Index.Game, result.toDelete))
              .rescue: e =>
                Logger[IO].error(e.asException)(
                  s"Failed to delete game: ${result.toDelete.map(_.value).mkString(", ")}"
                ) *> IO.raiseError(e.asException)
              .flatTap(_ => Logger[IO].info(s"Deleted ${result.toDelete.size} games"))
              .whenA(result.toDelete.nonEmpty)
      .compile
      .drain
