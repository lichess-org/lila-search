package lila.search
package clickhouse

import cats.effect.*
import doobie.Transactor
import doobie.implicits.*
import lila.search.clickhouse.game.*
import lila.search.game.Game
import lila.search.{ From, Size }

trait ClickHouseClient[F[_]]:
  def searchGames(q: Game, from: From, size: Size): F[List[String]]
  def countGames(q: Game): F[Long]
  def upsertGameRows(rows: List[GameRow]): F[Unit]
  def deleteGames(ids: List[String]): F[Unit]
  def createTable: F[Unit]
  def health: F[Boolean]
  def optimizePartition(partition: String): F[Unit]
  def optimizeAll: F[Unit]

object ClickHouseClient:

  def make(xa: Transactor[IO]): ClickHouseClient[IO] = new:
    def searchGames(q: Game, from: From, size: Size) =
      GameSearch.search(q, from, size).transact(xa)
    def countGames(q: Game) =
      GameSearch.count(q).transact(xa)
    def upsertGameRows(rows: List[GameRow]) =
      GameIngest.upsertGames(rows).transact(xa)
    def deleteGames(ids: List[String]) =
      GameIngest.deleteGames(ids).transact(xa)
    def createTable =
      GameTable.create.transact(xa).void
    def optimizePartition(partition: String) =
      GameIngest.optimizePartition(partition).transact(xa)
    def optimizeAll =
      GameIngest.optimizeAll.transact(xa)
    def health =
      sql"SELECT 1".query[Int].unique.transact(xa).map(_ == 1).handleError(_ => false)

  def resource(config: ClickHouseConfig): Resource[IO, ClickHouseClient[IO]] =
    ClickHouseTransactor.make(config).map(make)

  /**
   * A no-op client that does nothing. Useful for ElasticOnly mode where CH is not needed.
   */
  val noop: ClickHouseClient[IO] = new:
    def searchGames(q: lila.search.game.Game, from: From, size: Size) = IO.pure(Nil)
    def countGames(q: lila.search.game.Game) = IO.pure(0L)
    def upsertGameRows(rows: List[game.GameRow]) = IO.unit
    def deleteGames(ids: List[String]) = IO.unit
    def createTable = IO.unit
    def optimizePartition(partition: String) = IO.unit
    def optimizeAll = IO.unit
    def health = IO.pure(true)
