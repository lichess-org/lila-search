package lila.search
package ingestor

import cats.effect.IO
import lila.search.clickhouse.ClickHouseClient
import lila.search.ingestor.opts.{ OptimizeOpts, OptimizeTarget }
import org.typelevel.log4cats.{ Logger, LoggerFactory }

/**
 * Runs `OPTIMIZE TABLE games PARTITION ... FINAL`, which forces ClickHouse to merge all parts
 * within a partition into a single part, deduplicating rows in the process (ReplacingMergeTree
 * keeps the latest version of each `(date, id)` key).
 *
 * After optimization, `SELECT ... FROM games FINAL` on that partition is essentially free —
 * there's only one part, so there's nothing to deduplicate at read time.
 *
 * Without optimization, ClickHouse still merges parts in the background, but unmerged parts
 * accumulate during heavy writes. `FINAL` must then deduplicate across multiple parts at query
 * time, which gets expensive at scale.
 *
 * ### Usage
 *
 * {{{
 * # Optimize a single monthly partition
 * sbt 'ingestor-cli/run optimize --partition 202401'
 *
 * # Optimize the entire table (all partitions)
 * sbt 'ingestor-cli/run optimize --all'
 * }}}
 *
 * ### When to run
 *
 *   - **After backfill**: run `--all` once after the initial bulk import to fully merge all
 *     partitions.
 *   - **Historical partitions** (older than ~2 months): optimize once. They receive no new
 *     writes, so they stay fully merged forever.
 *   - **Recent partitions** (current and previous month): schedule nightly via cron during
 *     low-traffic hours. These partitions accumulate parts from continuous ingestion.
 *   - **After large batch re-indexes**: optimize the affected partitions afterward.
 *
 * ### Example cron (nightly at 04:00 UTC)
 *
 * {{{
 * 0 4 * * * /path/to/ingestor-cli optimize --partition $(date -u +\%Y\%m)
 * 0 4 1 * * /path/to/ingestor-cli optimize --partition $(date -u -d '1 month ago' +\%Y\%m)
 * }}}
 *
 * ### Performance considerations
 *
 *   - OPTIMIZE is I/O-intensive — it rewrites the entire partition. Schedule during low-traffic
 *     periods.
 *   - A single month at ~100M rows takes seconds to minutes depending on disk speed.
 *   - The table remains fully queryable during optimization.
 *   - Running OPTIMIZE on an already-merged partition is a no-op.
 *
 * ### Monitoring merge state
 *
 * {{{
 * SELECT partition, count() AS parts, sum(rows) AS total_rows
 * FROM system.parts
 * WHERE table = 'games' AND active
 * GROUP BY partition
 * ORDER BY partition DESC
 * }}}
 *
 * A partition with 1 part is fully optimized. Multiple parts mean `FINAL` has more work to do at
 * query time.
 */
class Optimizer(ch: ClickHouseClient[IO])(using LoggerFactory[IO]):

  given Logger[IO] = LoggerFactory[IO].getLogger

  def optimize(opts: OptimizeOpts): IO[Unit] =
    opts.target match
      case OptimizeTarget.Partition(p) =>
        Logger[IO].info(s"Optimizing partition $p") *>
          ch.optimizePartition(p) *>
          Logger[IO].info("Partition optimization complete")
      case OptimizeTarget.All =>
        Logger[IO].info("Optimizing all partitions") *>
          ch.optimizeAll *>
          Logger[IO].info("Full table optimization complete")
