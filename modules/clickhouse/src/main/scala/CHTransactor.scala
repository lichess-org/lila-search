package lila.search
package clickhouse

import cats.effect.*
import doobie.*
import doobie.hikari.HikariTransactor

object CHTransactor:

  case class Config(
      host: String = "localhost",
      port: Int = 8123, // HTTP port for JDBC (not 9000 which is native protocol)
      database: String = "lichess",
      user: String = "default",
      password: String = ""
  ):
    def jdbcUrl: String = s"jdbc:clickhouse://$host:$port/$database"

  /** Create a HikariCP connection pool for ClickHouse
    *
    * This is the recommended approach for production use as it provides:
    * - Connection pooling for better performance
    * - Connection validation and health checks
    * - Proper resource management
    */
  def makePooled(config: Config): Resource[IO, HikariTransactor[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](8) // Adjust pool size as needed
      xa <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "com.clickhouse.jdbc.ClickHouseDriver",
        url = config.jdbcUrl,
        user = config.user,
        pass = config.password,
        connectEC = ce
      )
      _ <- Resource.eval:
        xa.configure: ds =>
          IO:
            ds.setMaximumPoolSize(16) // Max connections
            ds.setMinimumIdle(2) // Keep 2 connections ready
            ds.setConnectionTimeout(10000) // 10 seconds
            ds.setIdleTimeout(600000) // 10 minutes
            ds.setMaxLifetime(1800000) // 30 minutes
            ds.setLeakDetectionThreshold(30000) // 30 seconds
    yield xa

  /** Create a simple connection (for testing/development)
    *
    * This is simpler but doesn't provide connection pooling. Use makePooled() for production.
    */
  def makeSimple(config: Config): Resource[IO, Transactor[IO]] =
    Resource
      .pure:
        Transactor.fromDriverManager[IO](
          driver = "com.clickhouse.jdbc.ClickHouseDriver",
          url = config.jdbcUrl,
          user = config.user,
          password = config.password,
          logHandler = None
        )
