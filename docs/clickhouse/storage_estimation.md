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
| `winner_color` | Nullable(Int8) | 2 | 1 null byte + 1 data |
| `date` | DateTime | 4 | 32-bit unix timestamp |
| `analysed` | Bool | 1 | |
| `white_user` | Nullable(String) | 12 | ~10 char avg username + null byte + length prefix |
| `black_user` | Nullable(String) | 12 | same |
| `avg_rating` | Nullable(UInt16) | 3 | null byte + 2 |
| `ai_level` | Nullable(UInt8) | 2 | null byte + 1 (mostly null) |
| `duration` | Nullable(UInt16) | 3 | null byte + 2 |
| `clock_init` | Nullable(UInt16) | 3 | null byte + 2 |
| `clock_inc` | Nullable(UInt16) | 3 | null byte + 2 |
| `source` | Nullable(UInt8) | 2 | null byte + 1 |
| **Total** | | **~60 bytes** | |

**10B rows x 60 bytes = ~600 GB uncompressed**

## Compression Estimates

ClickHouse columnar storage with ZSTD(1) and Delta coding compresses well for this schema:

- **Low-cardinality small integers** (`status`, `perf`, `source` as UInt8, `winner_color` as Int8): very few distinct values in 1-byte columns, 10-50x compression
- **Booleans** (`rated`, `analysed`): essentially bitmaps, 20-50x compression
- **Delta-coded DateTime** (`date`): ordered by date in sort key so deltas are tiny, 20-50x compression
- **Nullable columns that are mostly null** (`ai_level`): compress to almost nothing
- **Usernames**: moderate cardinality strings, ZSTD typically 2-4x

| Scenario | Compression Ratio | Estimated Size |
|---|---|---|
| Conservative | 5x | ~120 GB |
| Realistic | 7-8x | ~75-85 GB |
| Optimistic | 10x | ~60 GB |

## Additional Overhead

- **Bloom filter indexes** (`idx_white`, `idx_black` with 0.01 FPR): ~5-10% overhead (~4-9 GB)
- **Primary index**: one entry per granule (8192 rows), 10B/8192 = ~1.2M entries, negligible (~50 MB)
- **Partition metadata**: ~300 monthly partitions (lichess started 2010), negligible

## Total Estimate

**~80-130 GB on disk for 10 billion games**, realistic expectation around **80-95 GB**.

This is very manageable for a single ClickHouse node.

## Potential Optimizations

- Use `LowCardinality(Nullable(String))` for usernames if distinct count is under ~10M — could cut string storage in half
- Drop `Nullable` wrapper on columns that always have values — saves 1 byte per row per column
