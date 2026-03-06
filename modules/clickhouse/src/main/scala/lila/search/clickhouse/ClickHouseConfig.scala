package lila.search
package clickhouse

import cats.syntax.all.*
import ciris.*

case class ClickHouseConfig(
    url: String,
    user: String,
    password: String,
    maxPoolSize: Int,
    maxQueryMemoryUsage: Long,
    maxExecutionTime: Int
)

object ClickHouseConfig:
  def config: ConfigValue[Effect, ClickHouseConfig] = (
    env("CLICKHOUSE_URL")
      .or(prop("clickhouse.url"))
      .as[String]
      .default("jdbc:clickhouse://127.0.0.1:8123/lichess"),
    env("CLICKHOUSE_USER").or(prop("clickhouse.user")).as[String].default("default"),
    env("CLICKHOUSE_PASSWORD").or(prop("clickhouse.password")).as[String].default(""),
    env("CLICKHOUSE_MAX_POOL_SIZE").or(prop("clickhouse.max.pool.size")).as[Int].default(10),
    env("CLICKHOUSE_MAX_QUERY_MEMORY_USAGE")
      .or(prop("clickhouse.max.query.memory.usage"))
      .as[Long]
      .default(1_073_741_824L), // 1GB
    env("CLICKHOUSE_MAX_EXECUTION_TIME")
      .or(prop("clickhouse.max.execution.time"))
      .as[Int]
      .default(30) // seconds
  ).parMapN(ClickHouseConfig.apply)
