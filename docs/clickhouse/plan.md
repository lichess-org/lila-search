# ClickHouse Game Index Migration Plan

## Current State (branch `clickhouse/0`)

### Done and working

- **ClickHouse module** (`modules/clickhouse/`): table DDL, ingest, search, client, config, transactor
- **30+ integration tests** (ingest, search filters, sorting) with testcontainers
- **Search API** dispatches game queries via `GAME_SEARCH_BACKEND` env var (`elastic` | `clickhouse`)
- **CLI batch indexing** routes `Index.Game` to CH
- **Translate.toGameRow** conversion from MongoDB documents

### Key gap

The real-time game ingestor is **commented out** in `ingestors.scala:29,38` — no live ingestion to CH yet.

---

## Improvements Before Deployment

### P0 — Must fix

1. **Enable game ingestor** — Uncomment lines 29 and 38 in `ingestors.scala`. Without this, no live games flow to CH.

2. ~~**Query timeouts**~~ — Done. `max_execution_time` (default 30s) set at JDBC connection level via `ClickHouseConfig.maxExecutionTime`, configurable via `CLICKHOUSE_MAX_EXECUTION_TIME` env var.

3. **Switch to lightweight `DELETE`** — Current `ALTER TABLE DELETE WHERE id = $id SETTINGS mutations_sync=1` is a heavy mutation (rewrites entire parts). CH 23.3+ supports `DELETE FROM games WHERE id = $id` which is much lighter. Since we're on clickhouse-jdbc 0.9.7, the server should support this.

4. **Batch deletes** — `deleteGames` calls `deleteGame` one-by-one via `traverse_`. Should be a single `DELETE FROM games WHERE id IN (...)` using `Fragments.in`.

5. **Backfill scale test** — The current `fetchAll` streams MongoDB -> Scala -> JDBC -> CH. Need to benchmark throughput for the ~5B game backfill. If too slow, consider a CSV export + `clickhouse-client --query "INSERT INTO games FORMAT CSV"` approach.

### P1 — Production readiness

- [x]. **Dual-write ingestion** — During migration, ingest game events to **both** ES and CH. This keeps ES as a hot standby for rollback. Currently the architecture is either/or.

-[x] **Shadow query mode** — Add a `dual` backend option that queries both ES and CH, returns ES results, but logs any differences and CH latency. This validates correctness before cutover.

8. **Observability** — CH queries go through doobie/JDBC with zero metrics. Need:
   - Query latency histograms (search, count, upsert)
   - Error counters
   - Could wrap `ClickHouseClient` with a metrics decorator using otel4s (already in deps)

9. ~~**Query resource limits**~~ — Done. `max_memory_usage` (default 1GB) already set at connection level. `max_execution_time` (default 30s) now also set.

10. ~~**`FINAL` performance at scale**~~ — Done. `do_not_merge_across_partitions_select_final=1` set at connection level (safe: partition key guarantees duplicates are partition-local). `OPTIMIZE TABLE` available via CLI (`ingestor-cli optimize --partition YYYYMM` / `--all`) for pre-merging partitions so FINAL is nearly free. Benchmarking on real data still recommended before cutover.

### P2 — Future optimization

11. **User lookup projection** — Most queries filter by user. The bloom filter helps but a `users` Array(String) column with `arrayJoin` or a materialized view `(user, game_id)` would be much faster for user-scoped queries.

12. **Additional indexes** — `minmax` granularity indexes on `perf`, `rated`, `status` for better granule pruning.

13. **Percentage-based routing** — A `dual(N%)` backend that routes N% of game queries to CH, rest to ES. Enables gradual rollout.

14. **ReplicatedReplacingMergeTree** — Current DDL uses `ReplacingMergeTree()`. For HA, needs `ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/games', '{replica}')` with ClickHouse Keeper.

---

## Deployment Sequence

### Phase 1: Backfill

- Provision CH server (single node to start)
- CREATE TABLE (via `createTable` or DDL directly)
- Run backfill: `ingestor-cli --index game --since 0` (or CSV bulk import for speed)
- Verify row count matches MongoDB

### Phase 2: Dual-write

- Enable game ingestor in `Ingestors.scala`
- Deploy ingestor-app (writes game to both ES + CH)
- Verify CH stays in sync via spot checks
- Monitor CH ingest latency and error rate

### Phase 3: Shadow reads

- Deploy search API with shadow/dual mode
- Compare ES vs CH results (log mismatches)
- Benchmark CH query latency under production load
- Fix any query correctness issues

### Phase 4: Cutover

- Set `GAME_SEARCH_BACKEND=clickhouse` on canary instance
- Monitor error rate, latency, result quality
- Gradually shift all instances to clickhouse
- Keep ES dual-write running (hot standby)

### Phase 5: Cleanup

- Stop ES game ingestion
- Remove ES game index
- Remove `GameSearchBackend.ElasticOnly` code paths
- Drop ES game index data

### Rollback plan

At any phase, set `GAME_SEARCH_BACKEND=elastic` to revert search to ES. As long as dual-write is active, ES data is fresh.

---

## Storage Estimation

Rough estimate for 5B games:

- Raw row: ~100 bytes (strings are short IDs, most fields are 1-2 byte integers)
- ZSTD compression ratio: ~3-5x
- Estimated compressed storage: **100-200 GB**
- Fits comfortably on a single node with 500GB+ SSD
