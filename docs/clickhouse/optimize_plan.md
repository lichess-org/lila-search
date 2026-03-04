# ClickHouse Games Table Optimization Plan

Target: 10B+ games, growing continuously.

## Current Schema (problems)

```sql
ENGINE = ReplacingMergeTree()
ORDER BY id
-- No partitioning, no compression codecs, no skip indices
-- uids Array(String) column is redundant with white_user/black_user
```

- `ORDER BY id` provides no benefit for queries (most filter by user + date range)
- No partition pruning — every query scans all data
- Default LZ4 compression everywhere — suboptimal for sorted/low-cardinality columns
- `has(uids, $username)` scans the full Array column; white_user/black_user already exist

## Optimized Schema

```sql
CREATE TABLE IF NOT EXISTS games (
  id           String CODEC(LZ4),
  status       Int32 CODEC(ZSTD(1)),
  turns        Int32 CODEC(ZSTD(1)),
  rated        Bool CODEC(ZSTD(1)),
  perf         Int32 CODEC(ZSTD(1)),
  winner_color Nullable(Int8) CODEC(ZSTD(1)),
  date         DateTime CODEC(Delta, ZSTD(1)),
  analysed     Bool CODEC(ZSTD(1)),
  white_user   Nullable(String) CODEC(ZSTD(1)),
  black_user   Nullable(String) CODEC(ZSTD(1)),
  winner       Nullable(String) CODEC(ZSTD(1)),
  loser        Nullable(String) CODEC(ZSTD(1)),
  avg_rating   Nullable(Int32) CODEC(ZSTD(1)),
  ai_level     Nullable(Int32) CODEC(ZSTD(1)),
  duration     Nullable(Int32) CODEC(ZSTD(1)),
  clock_init   Nullable(Int32) CODEC(ZSTD(1)),
  clock_inc    Nullable(Int32) CODEC(ZSTD(1)),
  source       Nullable(Int32) CODEC(ZSTD(1)),

  INDEX idx_white white_user TYPE bloom_filter(0.01) GRANULARITY 1,
  INDEX idx_black black_user TYPE bloom_filter(0.01) GRANULARITY 1,
  INDEX idx_winner winner TYPE bloom_filter(0.01) GRANULARITY 1,
  INDEX idx_loser loser TYPE bloom_filter(0.01) GRANULARITY 1
) ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (date, id)
```

## Changes Summary

| Aspect | Before | After | Rationale |
|--------|--------|-------|-----------|
| Partitioning | None | `toYYYYMM(date)` | Monthly: ~192 partitions for 16 years; prunes irrelevant months on date range queries |
| Sort key | `ORDER BY id` | `ORDER BY (date, id)` | Date-first for range queries (most common filter + default sort); id for ReplacingMergeTree uniqueness |
| Compression | Default LZ4 | ZSTD(1) + Delta for date, LZ4 for id | ~4-5x compression ratio; Delta on sorted date is extremely effective |
| `uids` column | `Array(String)` | **Removed** | Redundant — white_user/black_user already exist; eliminates Array overhead |
| User query | `has(uids, $u)` | `white_user = $u OR black_user = $u` | Faster equality checks; works with bloom filter skip indices |
| Skip indices | None | bloom_filter on white_user, black_user, winner, loser | Skips ~99% of granules for user-specific queries |

## Design Decisions

### 1. Partitioning: `toYYYYMM(date)`

- Lichess spans ~16 years = ~192 monthly partitions (well within CH comfort zone of <1000)
- Most queries include date range — CH prunes irrelevant partitions entirely
- Recent months may hold 100M+ games — fine, CH manages parts within partitions
- Enables efficient ops (detach/attach old months, per-partition OPTIMIZE)
- Alternatives rejected: yearly (too coarse), daily (too many partitions ~5800)

### 2. Sorting Key: `(date, id)`

- Date is the most universal filter and default sort field
- Combined with monthly partitioning, date-sorted scans are near-free within a partition
- `id` provides uniqueness for ReplacingMergeTree dedup
- User queries rely on bloom filter skip indices instead of sort order

### 3. Compression: ZSTD(1) default, Delta+ZSTD for date, LZ4 for id

- `id` (unique strings): LZ4 — high entropy, ZSTD gains little
- `date` (DateTime, sorted): Delta encoding + ZSTD — delta is extremely effective on sorted timestamps
- Everything else: ZSTD(1) — ~4-5x compression with minimal CPU overhead
- Estimated storage: ~200-250 GB for 10B games (vs ~1 TB uncompressed)

### 4. No TTL

Lichess keeps all games forever. No data retention policy needed.

### 5. Bloom Filter Skip Indices

- GRANULARITY 1 = one bloom filter per granule (8192 rows)
- False positive rate 0.01 (1%) — good selectivity for high-cardinality user IDs
- At 10B rows (~1.2M granules), bloom filters eliminate >99% of granules for user queries
- Minimal storage overhead (~1-2% of table size)

## Future Optimizations (not implemented now)

### Projections for O(log n) User Lookups

If bloom filters prove insufficient at scale:

```sql
PROJECTION prj_white (SELECT * ORDER BY white_user, date)
PROJECTION prj_black (SELECT * ORDER BY black_user, date)
```

Doubles storage but makes user queries logarithmic instead of linear. Add only if needed.

### FINAL Overhead Mitigation

`ReplacingMergeTree` + `FINAL` forces in-memory dedup at query time. At 10B scale:
- Run `OPTIMIZE TABLE games PARTITION ... FINAL` periodically per partition
- Post-merge, FINAL is nearly free (no duplicates to filter)
- Consider scheduled maintenance job for recent partitions
