# ClickHouse Games Table Optimization Plan

Target: 10B+ games, growing continuously.

## Optimization Checklist

### Schema Optimizations

- [x] **Partitioning** — `PARTITION BY toYYYYMM(date)` (monthly, ~192 partitions for 16 years)
- [x] **Sort key** — `ORDER BY (date, id)` instead of `ORDER BY id` (date-first for range queries)
- [x] **Compression codecs** — ZSTD(1) everywhere, Delta+ZSTD for `date`, LZ4 for `id`
- [x] **Remove `uids` Array column** — redundant with `white_user`/`black_user`; user queries use `white_user = $u OR black_user = $u`
- [x] **Remove `winner`/`loser` columns** — derived at query time from `winner_color` + user columns (saves 2 string columns + 2 bloom filter indices)
- [x] **Bloom filter skip indices** — on `white_user` and `black_user` (GRANULARITY 1, FPR 0.01)
- [x] **Downsize integer types** — `UInt8` for status/perf/ai_level/source, `UInt16` for turns/ratings/duration/clock/chess960_pos (was `Int32`)
- [x] **Drop Nullable where possible** — `white_user`, `black_user`, `ai_level`, `duration` made non-nullable (saves 1 byte/row/column)
- [x] **Separate ratings** — `white_rating` + `black_rating` (both `UInt16`) instead of `avg_rating` (enables per-player rating filters)
- [x] **Chess960 position** — `chess960_pos UInt16` column (1000 if not Chess960)
- [ ] **Projections for O(log n) user lookups** — `PROJECTION prj_white (SELECT * ORDER BY white_user, date)` / `prj_black`. Doubles storage. Add only if bloom filters prove insufficient at scale.
- [ ] **`LowCardinality(String)` for usernames** — if distinct count is under ~10M, could cut string storage in half
- [ ] **FINAL overhead mitigation** — periodic `OPTIMIZE TABLE games PARTITION ... FINAL` to pre-merge duplicates so `FINAL` is nearly free at query time

## Current Schema

```sql
CREATE TABLE IF NOT EXISTS games (
  id           String CODEC(LZ4),
  status       UInt8 CODEC(ZSTD(1)),
  turns        UInt16 CODEC(ZSTD(1)),
  rated        Bool CODEC(ZSTD(1)),
  perf         UInt8 CODEC(ZSTD(1)),
  winner_color Enum8('unknown'=0, 'white'=1, 'black'=2, 'draw'=3) CODEC(ZSTD(1)),
  date         DateTime CODEC(Delta, ZSTD(1)),
  analysed     Bool CODEC(ZSTD(1)),
  white_user   String CODEC(ZSTD(1)),
  black_user   String CODEC(ZSTD(1)),
  white_rating UInt16 CODEC(ZSTD(1)),
  black_rating UInt16 CODEC(ZSTD(1)),
  ai_level     UInt8 CODEC(ZSTD(1)),
  duration     UInt16 CODEC(ZSTD(1)),
  clock_init   Nullable(UInt16) CODEC(ZSTD(1)),
  clock_inc    Nullable(UInt16) CODEC(ZSTD(1)),
  source       Nullable(UInt8) CODEC(ZSTD(1)),
  chess960_pos UInt16 CODEC(ZSTD(1)),

  INDEX idx_white white_user TYPE bloom_filter(0.01) GRANULARITY 1,
  INDEX idx_black black_user TYPE bloom_filter(0.01) GRANULARITY 1
) ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (date, id)
```

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

### 4. No TTL

Lichess keeps all games forever. No data retention policy needed.

### 5. Bloom Filter Skip Indices

- GRANULARITY 1 = one bloom filter per granule (8192 rows)
- False positive rate 0.01 (1%) — good selectivity for high-cardinality user IDs
- At 10B rows (~1.2M granules), bloom filters eliminate >99% of granules for user queries
- Minimal storage overhead (~1-2% of table size)

### 6. No winner/loser columns

Winner and loser queries are derived at query time from `winner_color` + `white_user`/`black_user`:
```sql
-- winner = 'user1'
(winner_color = 1 AND white_user = 'user1') OR (winner_color = 2 AND black_user = 'user1')
-- loser = 'user1'
(winner_color = 2 AND white_user = 'user1') OR (winner_color = 1 AND black_user = 'user1')
```
This avoids redundant string columns and their bloom filter indices, saving storage and ingestion cost.

### 7. Separate ratings instead of avgRating

`white_rating` and `black_rating` are stored independently (both `UInt16`), enabling per-player rating filters. The `avg_rating` filter in queries is computed as `(white_rating + black_rating) / 2`.
