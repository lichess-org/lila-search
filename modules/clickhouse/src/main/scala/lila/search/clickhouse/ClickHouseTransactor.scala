package lila.search
package clickhouse

import cats.effect.*
import com.zaxxer.hikari.HikariConfig
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Strategy

object ClickHouseTransactor:
  def make(config: ClickHouseConfig): Resource[IO, Transactor[IO]] =
    val hikariConfig = HikariConfig()
    hikariConfig.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver")
    hikariConfig.setJdbcUrl(config.url)
    hikariConfig.setUsername(config.user)
    hikariConfig.setPassword(config.password)
    hikariConfig.setMaximumPoolSize(config.maxPoolSize)
    hikariConfig.setAutoCommit(true)
    hikariConfig.setConnectionInitSql(
      s"SET max_memory_usage = ${config.maxQueryMemoryUsage}, max_execution_time = ${config.maxExecutionTime}, do_not_merge_across_partitions_select_final = 1"
    )
    // ClickHouse does not support transactions; use a void strategy
    // to prevent doobie from calling setAutoCommit/commit/rollback.
    HikariTransactor.fromHikariConfig[IO](hikariConfig).map(xa => Transactor.strategy.set(xa, Strategy.void))
