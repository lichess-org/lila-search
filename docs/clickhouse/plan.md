# ClickHouse Game Index Migration Plan

## Current State (branch `clickhouse/1`)

### Done and working

- **ClickHouse module** (`modules/clickhouse/`): table DDL (20 columns including `white_bot`/`black_bot`), ingest, search, client, config, transactor
- **33 integration tests** (ingest, search filters, sorting) with testcontainers
- **Search API** dispatches game queries via `GAME_SEARCH_BACKEND` env var (`elastic` | `clickhouse` | `dual`)
- **Ingestor** routes game events via `GAME_INGEST_BACKEND` env var (`elastic` | `clickhouse` | `both`)
- **Dual-write ingestion** — ingest to both ES and CH simultaneously via `broadcastThrough`
- **Shadow query mode** — `dual` backend queries both ES and CH, returns ES results, logs differences and CH latency via `DualMetrics`
- **Game request metrics** — `GameMetrics` records `game.request.duration` histogram for search/count operations across all backend modes
- **CLI batch indexing** routes `Index.Game` to CH
- **CLI optimizer** — `ingestor-cli optimize --partition YYYYMM` / `--all` for pre-merging partitions
- **Translate.toGameRow** conversion from MongoDB documents
- **Average rating filter** — excludes games with missing ratings (white_rating=0 or black_rating=0) when filtering by averageRating range, matching ES behavior
- **Query resource limits** — `max_memory_usage` (default 1GB), `max_execution_time` (default 60s) set at connection level
- **FINAL performance** — `do_not_merge_across_partitions_select_final=1` set at connection level

---

## Remaining Work Before Deployment

### P0 — Must fix

1. **Switch to lightweight `DELETE`** — Current `ALTER TABLE DELETE WHERE id = $id SETTINGS mutations_sync=1` is a heavy mutation (rewrites entire parts). CH 23.3+ supports `DELETE FROM games WHERE id = $id` which is much lighter. Since we're on clickhouse-jdbc 0.9.7, the server should support this.

2. **Batch deletes** — `deleteGames` calls `deleteGame` one-by-one via `traverse_`. Should be a single `DELETE FROM games WHERE id IN (...)` using `Fragments.in`.

3. **Backfill scale test** — The current `fetchAll` streams MongoDB -> Scala -> JDBC -> CH. Need to benchmark throughput for the ~10B game backfill. If too slow, consider a CSV export + `clickhouse-client --query "INSERT INTO games FORMAT CSV"` approach.

### P1 — Production readiness

4. **CH health in API** — Health endpoint only checks ES. In `ClickHouseOnly` mode, add CH to `/api/health`.

5. **Error handling for CH queries** — `chSearch()`/`chCount()` have no `rescue` wrapper. Add the same error handling pattern used for ES queries.

6. **Validate partition input** — `optimizePartition` uses `Fragment.const` with string interpolation. Validate format with regex `^\d{6}$`.

7. **Observability** — CH query-level metrics beyond game search (upsert latency, error counters). Could wrap `ClickHouseClient` with a metrics decorator using otel4s.

### P2 — Future optimization

8. **User lookup projection** — Most queries filter by user. The bloom filter helps but a materialized view `(user, game_id)` would be much faster for user-scoped queries at 10B+ scale.

9. **Additional indexes** — `minmax` granularity indexes on `perf`, `rated`, `status` for better granule pruning.

10. **Percentage-based routing** — A `dual(N%)` backend that routes N% of game queries to CH, rest to ES. Enables gradual rollout.

11. **ReplicatedReplacingMergeTree** — Current DDL uses `ReplacingMergeTree()`. For HA, needs `ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/games', '{replica}')` with ClickHouse Keeper.

---

## Deployment Sequence

### Phase 1: Backfill

- Provision CH server (single node to start)
- CREATE TABLE (via `createTable` or DDL directly)
- Run backfill: `ingestor-cli --index game --since 0` (or CSV bulk import for speed)
- Run `ingestor-cli optimize --all` to pre-merge all partitions
- Verify row count matches MongoDB

### Phase 2: Dual-write

- Deploy ingestor-app with `GAME_INGEST_BACKEND=both` (writes game to both ES + CH)
- Verify CH stays in sync via spot checks
- Monitor CH ingest latency and error rate

### Phase 3: Shadow reads

- Deploy search API with `GAME_SEARCH_BACKEND=dual`
- Compare ES vs CH results via `game.dual.result.diff` metric
- Benchmark CH query latency under production load via `game.dual.latency` metric
- Fix any query correctness issues

### Phase 4: Cutover

- Set `GAME_SEARCH_BACKEND=clickhouse` on canary instance
- Monitor error rate, latency, result quality via `game.request.duration` metric
- Gradually shift all instances to clickhouse
- Keep ES dual-write running (hot standby)

### Phase 5: Cleanup

- Stop ES game ingestion (`GAME_INGEST_BACKEND=clickhouse`)
- Remove ES game index
- Remove `GameSearchBackend.ElasticOnly` and `Dual` code paths
- Drop ES game index data

### Rollback plan

At any phase, set `GAME_SEARCH_BACKEND=elastic` to revert search to ES. As long as dual-write is active, ES data is fresh.

---

## Storage Estimation

Rough estimate for 10B games:

- Raw row: ~58 bytes (strings are short IDs, most fields are 1-2 byte integers)
- ZSTD compression ratio: ~7-8x
- Estimated compressed storage: **75-90 GB**
- Fits comfortably on a single node with 500GB+ SSD

See `storage_estimation.md` for detailed per-column breakdown.
