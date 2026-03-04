package lila.search
package ingestor.game

import cats.effect.IO
import cats.syntax.all.*
import lila.search.clickhouse.ClickHouseClient
import lila.search.clickhouse.game.GameRow
import lila.search.ingestor.{ DbGame, KVStore, Repo, Translate }

import java.time.Instant

object GameCHIngestor:

  def watch(
      repo: Repo[DbGame],
      ch: ClickHouseClient[IO],
      startAt: Option[Instant]
  )(using KVStore): IO[Unit] =
    fs2.Stream
      .eval(startAt.fold(summon[KVStore].get("game"))(_.some.pure[IO]))
      .flatMap(repo.watch)
      .evalMap: result =>
        ch.upsertGameRows(result.toIndex.map(toRow)) *>
          ch.deleteGames(result.toDelete.map(_.value)) *> // no need to delete
          result.timestamp.traverse_(summon[KVStore].put("game", _))
      .compile
      .drain

  def fetchAll(
      repo: Repo[DbGame],
      ch: ClickHouseClient[IO],
      since: Instant,
      until: Instant
  ): IO[Unit] =
    repo
      .fetchAll(since, until)
      .evalMap(result => ch.upsertGameRows(result.toIndex.map(toRow)))
      .compile
      .drain

  private def toRow(g: DbGame): GameRow =
    Translate.toGameRow(g)
