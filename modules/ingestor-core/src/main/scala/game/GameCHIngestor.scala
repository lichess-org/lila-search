package lila.search
package ingestor.game

import cats.effect.IO
import cats.syntax.all.*
import lila.search.clickhouse.ClickHouseClient
import lila.search.clickhouse.game.GameRow
import lila.search.ingestor.{ DbGame, KVStore, Repo, Translate }
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

object GameCHIngestor:

  def watch(
      index: Index,
      repo: Repo[DbGame],
      ch: ClickHouseClient[IO],
      startAt: Option[Instant],
      dry: Boolean
  )(using KVStore, LoggerFactory[IO]): IO[Unit] =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")
    fs2.Stream
      .eval(startAt.fold(KVStore.instance.get(index.value))(_.some.pure[IO]))
      .flatMap(repo.watch)
      .evalMap: result =>
        if dry then
          IO.println(s"Would upsert ${result.toIndex.size} games and delete ${result.toDelete.size} games")
        else
          logger.info(s"Updating ${result.toIndex.size} docs to ${index.value}") *>
            ch.upsertGameRows(result.toIndex.map(toRow)) *>
            ch.deleteGames(result.toDelete.map(_.value)) *> // no need to delete
            result.timestamp.traverse_(KVStore.instance.put(index.value, _))
      .compile
      .drain

  def fetchAll(
      index: Index,
      repo: Repo[DbGame],
      ch: ClickHouseClient[IO],
      since: Instant,
      until: Instant,
      dry: Boolean
  )(using LoggerFactory[IO]): IO[Unit] =
    given logger: Logger[IO] = LoggerFactory[IO].getLoggerFromName(s"${index.value}.ingestor")
    repo
      .fetchAll(since, until)
      .evalMap: result =>
        if dry then
          IO.println(s"Would upsert ${result.toIndex.size} games and delete ${result.toDelete.size} games")
        else
          logger.info(s"Updating ${result.toIndex.size} docs to ${index.value}") *>
            ch.upsertGameRows(result.toIndex.map(toRow))
      .compile
      .drain

  private def toRow(g: DbGame): GameRow =
    Translate.toGameRow(g)
