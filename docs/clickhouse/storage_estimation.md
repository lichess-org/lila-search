# ClickHouse Storage Estimation: Game Index (10B rows)

Based on the schema in `GameTable.scala` (`ReplacingMergeTree`, partitioned by `toYYYYMM(date)`, ordered by `(date, id)`).

## Per-Row Uncompressed Size

| Column | Type | Avg Bytes | Notes |
|---|---|---|---|
| `id` | String | 8 | lichess game IDs are 8 chars |
| `status` | UInt8 | 1 | |
| `turns` | UInt16 | 2 | |
| `rated` | Bool | 1 | |
| `perf` | UInt8 | 1 | |
| `winner_color` | Enum8 | 1 | 4 values: unknown/white/black/draw |
| `date` | DateTime | 4 | 32-bit unix timestamp |
| `analysed` | Bool | 1 | |
| `white_user` | String | 11 | ~10 char avg username + length prefix |
| `black_user` | String | 11 | same |
| `white_rating` | UInt16 | 2 | |
| `black_rating` | UInt16 | 2 | |
| `ai_level` | UInt8 | 1 | 0 if human game |
| `duration` | UInt16 | 2 | seconds, capped at 12h+1s |
| `clock_init` | Nullable(UInt16) | 3 | null byte + 2 |
| `clock_inc` | Nullable(UInt16) | 3 | null byte + 2 |
| `source` | Nullable(UInt8) | 2 | null byte + 1 |
| `chess960_pos` | UInt16 | 2 | 1000 if not Chess960 |
| **Total** | | **~58 bytes** | |

**10B rows x 58 bytes = ~580 GB uncompressed**

## Compression Estimates

ClickHouse columnar storage with ZSTD(1) and Delta coding compresses well for this schema:

- **Low-cardinality small integers** (`status`, `perf`, `source` as UInt8, `winner_color` as Enum8): very few distinct values in 1-byte columns, 10-50x compression
- **Booleans** (`rated`, `analysed`): essentially bitmaps, 20-50x compression
- **Delta-coded DateTime** (`date`): ordered by date in sort key so deltas are tiny, 20-50x compression
- **Small integers that are often 0** (`ai_level`, `chess960_pos`): compress very well
- **Rating columns** (`white_rating`, `black_rating`): low entropy UInt16, ~5-10x compression
- **Usernames**: moderate cardinality strings, ZSTD typically 2-4x

| Scenario | Compression Ratio | Estimated Size |
|---|---|---|
| Conservative | 5x | ~116 GB |
| Realistic | 7-8x | ~73-83 GB |
| Optimistic | 10x | ~58 GB |

## Additional Overhead

- **Bloom filter indexes** (`idx_white`, `idx_black` with 0.01 FPR): ~5-10% overhead (~4-8 GB)
- **Primary index**: one entry per granule (8192 rows), 10B/8192 = ~1.2M entries, negligible (~50 MB)
- **Partition metadata**: ~192 monthly partitions (lichess started ~2010), negligible

## Total Estimate

**~75-125 GB on disk for 10 billion games**, realistic expectation around **75-90 GB**.

This is very manageable for a single ClickHouse node.

## Storage Optimization Checklist

- [x] **Drop Nullable where possible** — `white_user`, `black_user`, `ai_level`, `duration` made non-nullable (saves 1 byte/row/column)
- [x] **Downsize integer types** — `UInt8`/`UInt16` instead of `Int32` across all columns
- [x] **Remove redundant columns** — dropped `winner`, `loser`, `uids` (derived at query time or redundant)
- [ ] **Use `LowCardinality(String)` for usernames** — if distinct count is under ~10M, could cut string storage in half
