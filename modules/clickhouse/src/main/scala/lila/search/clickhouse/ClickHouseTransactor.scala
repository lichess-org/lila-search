package lila.search
package clickhouse

import cats.effect.*
import doobie.Transactor
import doobie.hikari.HikariTransactor

import scala.concurrent.ExecutionContext

object ClickHouseTransactor:
  def make(config: ClickHouseConfig): Resource[IO, Transactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "com.clickhouse.jdbc.ClickHouseDriver",
      url = config.url,
      user = config.user,
      pass = config.password,
      connectEC = ExecutionContext.global
    )
