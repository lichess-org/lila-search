# ClickHouse Migration Plan: Game Index

## Executive Summary

Migrate the game index from Elasticsearch to ClickHouse to address performance issues with complex filtering and aggregation queries. This is a phased, low-risk migration focused solely on the game index while keeping other indexes (forum, study, team, ublog) in Elasticsearch.

**Key Success Factors:**
- Game queries are pure filters/ranges - NO full-text search (perfect for ClickHouse)
- Schema-driven approach preserved (Smithy → ClickHouse DDL generation)
- Dual-write strategy enables gradual rollout with quick rollback capability
- Existing query patterns map cleanly to SQL

## Current Architecture Analysis

### Data Flow
```
MongoDB Change Stream → Repo[DbGame] → Translate.game → GameSource → JSON → Elasticsearch
```

### Query Patterns (modules/elastic/src/main/scala/game.scala:49-82)
All queries are ClickHouse-friendly:
- **Term queries**: Exact matches (user, winner, loser, perf, status, etc.)
- **Range queries**: Numbers and dates (turns, averageRating, duration, date, aiLevel)
- **Terms queries**: IN clauses (perf list, clockInit, clockInc)
- **Exists queries**: NULL checks (hasAi)
- **Sorting**: By date (default), turns, or averageRating

### Schema (modules/elastic/src/main/smithy/model.smithy:43-105)
18 fields in GameSource, all map cleanly to ClickHouse types:
- Integers: status, turns, perf, winnerColor, ai, duration, clockInit, clockInc, source
- Booleans: rated, analysed
- Strings: winner, loser, whiteUser, blackUser, uids (PlayerIds)
- Date: date (DateTime with format)
- Shorts: turns, averageRating, ai, clockInc (optimize storage)

## Target ClickHouse Architecture

### Module Structure

```
lila-search/
├── modules/
│   ├── clickhouse/                    # NEW: ClickHouse backend
│   │   ├── src/main/smithy/
│   │   │   └── clickhouse.smithy      # ClickHouse field traits (@intColumn, @dateColumn, etc.)
│   │   ├── src/main/scala/
│   │   │   ├── ClickHouseClient.scala # Client wrapper (connection pooling, query execution)
│   │   │   ├── SchemaGenerator.scala  # Smithy → CREATE TABLE DDL
│   │   │   ├── QueryBuilder.scala     # Scala DSL → ClickHouse SQL
│   │   │   └── game-ch.scala          # Game-specific CH queries
│   │   └── src/test/scala/
│   │       └── SchemaGenerationSuite.scala
│   │
│   ├── elastic/                        # EXISTING: Keep for other indexes
│   │   └── ... (no changes)
│   │
│   ├── ingestor-core/                  # MODIFIED: Abstract over backends
│   │   ├── src/main/scala/
│   │   │   ├── IndexRegistry.scala     # Support both ES and CH backends
│   │   │   ├── SearchBackend.scala     # NEW: Backend abstraction trait
│   │   │   ├── ESBackend.scala         # NEW: ES implementation
│   │   │   ├── CHBackend.scala         # NEW: ClickHouse implementation
│   │   │   └── Translate.scala         # Keep as-is (reuse for both)
│   │
│   ├── ingestor-app/                   # MODIFIED: Support dual-write
│   │   └── Main.scala                  # Add --backend flag, dual-write mode
│   │
│   └── ingestor-cli/                   # MODIFIED: Support CH batch indexing
│       └── Main.scala                  # Add --backend clickhouse flag
```

### ClickHouse Schema Design

**Table Engine**: `ReplacingMergeTree`
- Handles updates to games (analysis added, tags modified)
- Uses `date` as version column (games don't change after that timestamp)
- Automatic deduplication on background merges

**Partitioning**: By month (`toYYYYMM(date)`)
- Aligns with common query patterns (recent games)
- Efficient partition pruning for date range queries
- Manageable partition size

**Primary Key (ORDER BY)**: `(perfType, date, id)`
- `perfType` first: Most queries filter by perf type (bullet, blitz, etc.)
- `date` second: Time-based queries are common, enables range scans
- `id` last: Ensures uniqueness, helps with ReplacingMergeTree deduplication

**Proposed DDL** (generated from Smithy):

**Single-Instance Deployment** (Recommended for initial deployment):
```sql
CREATE TABLE lichess.game
(
    -- Primary fields
    id String,
    status UInt8,
    turns UInt16,
    rated UInt8,  -- Boolean as UInt8
    perf UInt8,

    -- Player fields
    uids Array(String),
    winner LowCardinality(String),
    loser LowCardinality(String),
    winnerColor UInt8,
    whiteUser LowCardinality(String),
    blackUser LowCardinality(String),

    -- Rating and AI
    averageRating Nullable(UInt16),
    ai Nullable(UInt8),

    -- Time fields
    date DateTime,
    duration Nullable(UInt32),
    clockInit Nullable(UInt32),
    clockInc Nullable(UInt16),

    -- Metadata
    analysed UInt8,
    source Nullable(UInt8),

    -- Version for ReplacingMergeTree
    _version DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(_version)
PARTITION BY toYYYYMM(date)
ORDER BY (perf, date, id)
SETTINGS index_granularity = 8192;

-- Secondary indexes for non-primary key queries
ALTER TABLE lichess.game ADD INDEX idx_status status TYPE minmax GRANULARITY 4;
ALTER TABLE lichess.game ADD INDEX idx_users uids TYPE bloom_filter GRANULARITY 1;
ALTER TABLE lichess.game ADD INDEX idx_rating averageRating TYPE minmax GRANULARITY 4;
```

**Notes on Single Instance**:
- Uses `ReplacingMergeTree` instead of `ReplicatedReplacingMergeTree`
- No `ON CLUSTER` clause needed
- Simpler deployment and operational overhead
- Can migrate to clustered setup later if needed
- For production: Consider regular backups since no replication

**Type Mappings**:
- Smithy `Integer` → `UInt8` (for status, perf, source with small range)
- Smithy `Integer` (large range) → `UInt32` (duration, clockInit)
- Smithy `Integer` (short) → `UInt16` (turns, averageRating, clockInc)
- Smithy `Boolean` → `UInt8` (0/1)
- Smithy `String` (high-cardinality) → `LowCardinality(String)` (usernames)
- Smithy `String` (low-cardinality) → `String` (id)
- Smithy `PlayerIds` → `Array(String)`
- Smithy `DateTime` → `DateTime`

### Query Translation Layer

**Approach**: Build a query translator that maps current query patterns to SQL.

**Example Translation**:
```scala
// Current (game.scala:49-82)
case class Game(
  user1: Option[String] = None,
  perf: List[Int] = List.empty,
  turns: Range[Int] = Range.none,
  date: Range[Instant] = Range.none,
  // ...
)

// Translates to ClickHouse SQL:
SELECT id FROM lichess.game
WHERE hasAny(uids, ['user1'])
  AND perf IN (1, 2, 6)
  AND turns BETWEEN 20 AND 50
  AND date >= '2024-01-01' AND date < '2024-02-01'
ORDER BY date DESC
LIMIT 50 OFFSET 0
```

**Query Builder Design (using Doobie Fragments)**:
```scala
import doobie.*
import doobie.implicits.*

// Composable query fragments
object CHQueryBuilder:

  // Term query: exact match
  def termQuery(field: String, value: String): Fragment =
    Fragment.const(field) ++ fr"= $value"

  def termQueryInt(field: String, value: Int): Fragment =
    Fragment.const(field) ++ fr"= $value"

  // Range query: optional min/max
  def rangeQuery[A: Put](field: String, min: Option[A], max: Option[A]): Option[Fragment] =
    (min, max) match
      case (Some(a), Some(b)) => Some(Fragment.const(field) ++ fr"BETWEEN $a AND $b")
      case (Some(a), None)    => Some(Fragment.const(field) ++ fr">= $a")
      case (None, Some(b))    => Some(Fragment.const(field) ++ fr"<= $b")
      case (None, None)       => None

  // Terms query: IN clause
  def termsQuery[A: Put](field: String, values: List[A]): Option[Fragment] =
    values match
      case Nil => None
      case xs  => Some(Fragment.const(field) ++ fr"IN (" ++ xs.map(v => fr"$v").intercalate(fr",") ++ fr")")

  // Array contains query (for uids field)
  def arrayContains(field: String, value: String): Fragment =
    fr"hasAny(" ++ Fragment.const(field) ++ fr", ARRAY[$value])"

  // Exists query: IS NOT NULL
  def existsQuery(field: String): Fragment =
    Fragment.const(field) ++ fr"IS NOT NULL"

  def notExistsQuery(field: String): Fragment =
    Fragment.const(field) ++ fr"IS NULL"

  // Combine fragments with AND
  def and(fragments: List[Fragment]): Fragment =
    fragments match
      case Nil => fr"1=1"  // Always true
      case xs  => xs.intercalate(fr"AND")

  // Combine fragments with OR
  def or(fragments: List[Fragment]): Fragment =
    fragments match
      case Nil => fr"1=0"  // Always false
      case xs  => fr"(" ++ xs.intercalate(fr"OR") ++ fr")"
```

**Benefits of Doobie Fragments**:
- **Type-safe**: Parameter types checked at compile time
- **Composable**: Build complex queries from small fragments
- **SQL injection safe**: All parameters are properly escaped
- **Readable**: SQL looks like SQL, not string concatenation

### Data Ingestion Pipeline

**Batching Strategy** (critical for ClickHouse performance):

```scala
import doobie.*
import doobie.implicits.*
import doobie.util.fragment.Fragment

class CHIngestor(xa: Transactor[IO], batchSize: Int = 1000, flushInterval: FiniteDuration = 5.seconds):

  // Doobie Write instance for GameSource
  given Write[GameSource] =
    Write[(String, Int, Int, Boolean, Int, Array[String], Option[String], Option[String], Int,
           Option[Int], Option[Int], LocalDateTime, Option[Int], Option[Int], Option[Int],
           Boolean, Option[String], Option[String], Option[Int])]
      .contramap: g =>
        (g.id, g.status, g.turns, g.rated, g.perf, g.uids.toArray, g.winner, g.loser,
         g.winnerColor, g.averageRating, g.ai, g.date, g.duration, g.clockInit,
         g.clockInc, g.analysed, g.whiteUser, g.blackUser, g.source)

  def ingest(stream: Stream[IO, GameSource]): IO[Unit] =
    stream
      .groupWithin(batchSize, flushInterval)  // Reuse existing batching!
      .evalMap: batch =>
        insertBatch(batch.toList)
      .compile
      .drain

  private def insertBatch(games: List[GameSource]): IO[Unit] =
    val sql =
      """INSERT INTO lichess.game
         (id, status, turns, rated, perf, uids, winner, loser, winnerColor,
          averageRating, ai, date, duration, clockInit, clockInc, analysed,
          whiteUser, blackUser, source)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

    Update[GameSource](sql)
      .updateMany(games)
      .transact(xa)
      .void
```

**Key Benefits of Doobie for Batching**:
- **updateMany**: Optimized for batch inserts (single round-trip)
- **Type-safe**: Write instance ensures all fields are correctly mapped
- **Reuse existing batching**: `groupWithin` pattern works perfectly
- **Transactional**: Batch succeeds or fails atomically

**Repo Abstraction** (unchanged):
```scala
// Existing Repo abstraction works as-is
trait Repo[A]:
  def watch(since: Instant): Stream[IO, Result[A]]
  def fetch(id: String): IO[Option[A]]
  def count(since: Instant): IO[Long]
```

## Implementation Phases

### Phase 1: Foundation (Weeks 1-2)
**Goal**: Set up ClickHouse infrastructure and basic connectivity

**Tasks**:
1. **ClickHouse Single-Instance Setup**

   **Deployment Options**:

   **Option A: Docker Compose (Recommended for Development)**

   ✅ **Already configured!** ClickHouse has been added to `../docker/docker-compose.yml`

   ```bash
   # Navigate to docker directory
   cd ../docker

   # Start ClickHouse with lila-search services
   docker compose --profile lila-search up -d

   # Or start only ClickHouse
   docker compose --profile clickhouse up -d

   # Run setup script to initialize database and test data
   ./scripts/setup-clickhouse.sh

   # Verify it's running
   curl http://localhost:8123/ping
   # Should return: Ok.

   # Access Web UI
   open http://localhost:8093
   ```

   **Configuration**:
   - **HTTP Interface**: `http://localhost:8123`
   - **Native Protocol**: `localhost:9000` (for JDBC/Doobie)
   - **Web UI (Tabix)**: `http://localhost:8093`
   - **Container Name**: `lila_clickhouse`
   - **Network**: `lila-network` (172.22.0.18)
   - **Database**: `lichess`
   - **Volumes**:
     - Data: `data-clickhouse` volume
     - Config: `../docker/conf/clickhouse/`
     - Backups: `../docker/clickhouse-backups/`

   See `../docker/CLICKHOUSE.md` for complete documentation.

   **Option B: Standalone Docker (Alternative)**
   ```bash
   docker run -d \
     --name clickhouse-server \
     -p 8123:8123 \
     -p 9000:9000 \
     --ulimit nofile=262144:262144 \
     -v /path/to/data:/var/lib/clickhouse \
     clickhouse/clickhouse-server:24.11-alpine
   ```

   **Option C: Native Installation (Production)**
   ```bash
   # Ubuntu/Debian
   sudo apt-get install -y apt-transport-https ca-certificates dirmngr
   sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 8919F6BD2B48D754
   echo "deb https://packages.clickhouse.com/deb stable main" | sudo tee /etc/apt/sources.list.d/clickhouse.list
   sudo apt-get update
   sudo apt-get install -y clickhouse-server clickhouse-client
   sudo service clickhouse-server start
   ```

   **Configuration** (`/etc/clickhouse-server/config.xml`):
   ```xml
   <clickhouse>
     <!-- Listen on all interfaces (or specific IP) -->
     <listen_host>0.0.0.0</listen_host>

     <!-- Memory limits -->
     <max_server_memory_usage_to_ram_ratio>0.8</max_server_memory_usage_to_ram_ratio>

     <!-- Logging -->
     <logger>
       <level>information</level>
       <log>/var/log/clickhouse-server/clickhouse-server.log</log>
       <errorlog>/var/log/clickhouse-server/clickhouse-server.err.log</errorlog>
       <size>1000M</size>
       <count>10</count>
     </logger>

     <!-- Disable default user password (set in users.xml) -->
   </clickhouse>
   ```

   **Monitoring**:
   - Set up Prometheus exporter: https://github.com/ClickHouse/clickhouse_exporter
   - Grafana dashboard: https://grafana.com/grafana/dashboards/882
   - Monitor key metrics: queries/sec, memory usage, merge activity

   **Backup Strategy** (critical for single instance):
   ```bash
   # Option 1: clickhouse-backup tool
   clickhouse-backup create
   clickhouse-backup upload <backup_name>

   # Option 2: Manual backup
   # Stop writes, then:
   sudo -u clickhouse clickhouse-client --query "BACKUP TABLE lichess.game TO Disk('backups', 'game_backup')"
   ```

   **Why Single Instance is Fine**:
   - ✅ **Simplicity**: No ZooKeeper/Keeper coordination needed
   - ✅ **Lower cost**: Single server reduces infrastructure spend
   - ✅ **Easier operations**: Simpler deployment, monitoring, debugging
   - ✅ **Performance**: Single instance can handle millions of games easily
   - ✅ **Migration path**: Can add replication later if needed

   **When to Consider Clustering**:
   - ⚠️ Data size exceeds single server capacity (>10TB)
   - ⚠️ Query load requires horizontal scaling (>10k qps)
   - ⚠️ High availability requirements (99.99%+ uptime)
   - ⚠️ Geographic distribution needed

2. **Create `clickhouse` Module**
   - Add to `build.sbt`:
     ```scala
     lazy val clickhouse = project
       .in(file("modules/clickhouse"))
       .enablePlugins(Smithy4sCodegenPlugin)
       .settings(
         name := "clickhouse",
         commonSettings,
         libraryDependencies ++= Seq(
           "org.tpolecat" %% "doobie-core" % "1.0.0-RC5",
           "com.clickhouse" % "clickhouse-jdbc" % "0.6.3",
           "com.clickhouse" % "clickhouse-http-client" % "0.6.3",
           catsEffect,
           fs2Core
         )
       )
       .dependsOn(core)
     ```

   **Why Doobie?**
   - Pure functional JDBC layer, integrates with cats-effect/fs2
   - Composable query building using `Fragment`
   - Type-safe parameter binding and result extraction
   - Built-in connection pooling support
   - Excellent testing utilities

3. **Smithy Traits for ClickHouse**
   - Create `modules/clickhouse/src/main/smithy/clickhouse.smithy`:
     ```smithy
     namespace lila.search.ch

     @trait(selector: "member")
     structure intColumn { nullable: Boolean }

     @trait(selector: "member")
     structure dateColumn { nullable: Boolean }

     @trait(selector: "member")
     structure stringColumn {
       nullable: Boolean
       lowCardinality: Boolean
     }

     @trait(selector: "member")
     structure arrayColumn { elementType: String }

     // ... more traits
     ```

4. **ClickHouse Client Wrapper (using Doobie)**

   **Connection Configuration**:
   ```scala
   // ClickHouseTransactor.scala
   package lila.search.clickhouse

   import doobie.*
   import doobie.implicits.*
   import cats.effect.*

   object ClickHouseTransactor:
     def make(config: CHConfig): Resource[IO, Transactor[IO]] =
       for
         ce <- ExecutionContexts.fixedThreadPool[IO](32)
         xa <- Transactor.fromDriverManager[IO](
           driver = "com.clickhouse.jdbc.ClickHouseDriver",
           url = s"jdbc:clickhouse://${config.host}:${config.port}/${config.database}",
           user = config.user,
           password = config.password,
           logHandler = None  // Or custom logging
         ).pure[Resource[IO, *]]
       yield xa
   ```

   **Client Interface**:
   ```scala
   // CHClient.scala
   trait CHClient[F[_]]:
     def execute(sql: Fragment): F[Unit]
     def query[A: Read](sql: Fragment): F[List[A]]
     def insertBatch[A](table: String, rows: List[A])(using writer: Write[A]): F[Unit]
     def count(sql: Fragment): F[Long]

   class DoobieCHClient(xa: Transactor[IO]) extends CHClient[IO]:
     def execute(sql: Fragment): IO[Unit] =
       sql.update.run.transact(xa).void

     def query[A: Read](sql: Fragment): IO[List[A]] =
       sql.query[A].to[List].transact(xa)

     def insertBatch[A](table: String, rows: List[A])(using writer: Write[A]): IO[Unit] =
       // ClickHouse-optimized batch insert
       val values = rows.map(row => fr"(" ++ writeFragment(row) ++ fr")")
       val sql = fr"INSERT INTO" ++ Fragment.const(table) ++
                 fr"VALUES" ++ values.intercalate(fr",")
       sql.update.run.transact(xa).void

     def count(sql: Fragment): IO[Long] =
       sql.query[Long].unique.transact(xa)
   ```

5. **Schema Generator**
   - Read Smithy schema (reuse `es.GameSource` or create `ch.GameSource`)
   - Generate CREATE TABLE DDL
   - Tests: verify generated DDL matches expectations

**Deliverable**: ClickHouse cluster running, `clickhouse` module compiles, can connect and create tables

---

### Phase 2: Query Translation (Weeks 3-4)
**Goal**: Translate existing game queries to ClickHouse SQL

**Tasks**:
1. **Create Query Builder**
   - Implement `CHQueryBuilder` trait
   - Map term/range/terms/exists queries to SQL
   - Handle sorting and pagination

2. **Create game-ch.scala** (Doobie-based)
   - Mirror structure of `game.scala`
   - Reuse `Game` case class (same query parameters)
   - Build queries using Doobie Fragments

   ```scala
   package lila.search.game.clickhouse

   import doobie.*
   import doobie.implicits.*
   import lila.search.game.{ Game, Fields, Sorting }
   import CHQueryBuilder.*

   object GameCH:

     // Build WHERE clause from Game query
     def buildFilters(query: Game): Fragment =
       val filters = List(
         // User queries (array contains)
         query.user1.map(u => arrayContains(Fields.uids, u)),
         query.user2.map(u => arrayContains(Fields.uids, u)),

         // Term queries
         query.winner.map(termQuery(Fields.winner, _)),
         query.loser.map(termQuery(Fields.loser, _)),
         query.winnerColor.map(termQueryInt(Fields.winnerColor, _)),
         query.status.map(termQueryInt(Fields.status, _)),
         query.source.map(termQueryInt(Fields.source, _)),
         query.clockInit.map(termQueryInt(Fields.clockInit, _)),
         query.clockInc.map(termQueryInt(Fields.clockInc, _)),
         query.whiteUser.map(termQuery(Fields.whiteUser, _)),
         query.blackUser.map(termQuery(Fields.blackUser, _)),

         // Boolean queries
         query.rated.map(r => termQueryInt(Fields.rated, if r then 1 else 0)),
         query.analysed.map(a => termQueryInt(Fields.analysed, if a then 1 else 0)),

         // Terms query (IN clause)
         termsQuery(Fields.perf, query.perf),

         // Range queries
         rangeQuery(Fields.turns, query.turns.min, query.turns.max),
         rangeQuery(Fields.averageRating, query.averageRating.min, query.averageRating.max),
         rangeQuery(Fields.duration, query.duration.min, query.duration.max),
         rangeQuery(Fields.date, query.date.min, query.date.max),

         // AI queries
         query.hasAi.map: hasAi =>
           if hasAi then existsQuery(Fields.ai)
           else notExistsQuery(Fields.ai)
         ,
         query.hasAi.filter(_ == true).flatMap: _ =>
           rangeQuery(Fields.ai, query.aiLevel.min, query.aiLevel.max)
       ).flatten

       and(filters)

     // Build ORDER BY clause
     def buildSort(sorting: Sorting): Fragment =
       val field = Fragment.const(sorting.f)
       val order = if sorting.order.toLowerCase == "asc" then fr"ASC" else fr"DESC"
       fr"ORDER BY" ++ field ++ order

     // Complete query
     def searchQuery(query: Game, from: From, size: Size): Query0[String] =
       (fr"SELECT id FROM lichess.game" ++
        fr"WHERE" ++ buildFilters(query) ++
        buildSort(query.sorting) ++
        fr"LIMIT ${size.value} OFFSET ${from.value}")
         .query[String]

     def countQuery(query: Game): Query0[Long] =
       (fr"SELECT COUNT(*) FROM lichess.game" ++
        fr"WHERE" ++ buildFilters(query))
         .query[Long]
   ```

   **Key Advantages**:
   - **Composable**: Build complex queries from fragments
   - **Type-safe**: Compiler checks parameter types
   - **Reusable**: `buildFilters` shared between search and count
   - **Testable**: Can inspect generated SQL without executing

3. **Query Equivalence Tests** (using Doobie test utilities)

   **Add Doobie test dependencies**:
   ```scala
   libraryDependencies ++= Seq(
     "org.tpolecat" %% "doobie-scalatest" % "1.0.0-RC5" % Test,
     "org.tpolecat" %% "doobie-specs2" % "1.0.0-RC5" % Test
   )
   ```

   **Query Testing Pattern**:
   ```scala
   import doobie.scalatest.*

   class GameCHSuite extends IOChecker with IntegrationSuite:

     // Check query compiles and type-checks
     test("searchQuery type-checks"):
       val query = Game(
         user1 = Some("alice"),
         perf = List(1, 2),
         turns = Range(Some(20), Some(50))
       )
       check(GameCH.searchQuery(query, From(0), Size(20)))

     // Check count query
     test("countQuery type-checks"):
       val query = Game(user1 = Some("alice"))
       check(GameCH.countQuery(query))

     // Query equivalence test
     test("CH returns same results as ES"):
       for
         // Setup test data in both ES and CH
         _ <- esClient.store(Index.Game, Id("game1"), gameSource1)
         _ <- chClient.insertBatch("lichess.game", List(gameSource1))

         // Run same query on both
         query = Game(user1 = Some("alice"), perf = List(1, 2))
         esResults <- esClient.search(query, From(0), Size(20))
         chResults <- GameCH.searchQuery(query, From(0), Size(20))
                        .to[List]
                        .transact(xa)

         // Compare results
       yield expect(esResults.map(_.id) == chResults)
   ```

   **Benefits of Doobie Testing**:
   - **check()**: Validates query compiles and types match at runtime
   - **IOChecker**: Integrates with Weaver/ScalaTest test frameworks
   - **Type safety**: Catches mismatches before hitting production
   - **SQL inspection**: Can see generated SQL in test output

   **Example Test Output**:
   ```
   ✓ SELECT id FROM lichess.game
       WHERE uids = ? AND perf IN (?, ?)
       ORDER BY date DESC
       LIMIT ? OFFSET ?

     ✓ SQL compiles and type-checks
     ✓ Parameter types: String, Int, Int, Int, Int
     ✓ Result type: String
   ```

**Deliverable**: Can translate all game query patterns to SQL, tests pass, queries type-check

---

### Phase 3: Ingestion Pipeline (Weeks 5-6)
**Goal**: Index game data to ClickHouse

**Tasks**:
1. **Backend Abstraction**
   - Create `SearchBackend[F[_]]` trait:
     ```scala
     trait SearchBackend[F[_]]:
       def index[A: Encoder](index: String, id: String, doc: A): F[Unit]
       def indexBatch[A: Encoder](index: String, docs: List[(String, A)]): F[Unit]
       def delete(index: String, id: String): F[Unit]
       def search[A: Decoder](index: String, query: Query): F[SearchResults[A]]
     ```

   - Implement `ESBackend` (wraps existing ES client)
   - Implement `CHBackend` (wraps ClickHouse client with batching)

2. **Update IndexRegistry**
   - Support backend selection:
     ```scala
     class IndexRegistry(
       game: IO[Repo[DbGame]],
       backend: SearchBackend[IO],  // ES or CH
       // ...
     )
     ```

3. **Batch Indexing CLI**
   - Add `--backend clickhouse` flag to `ingestor-cli`:
     ```bash
     sbt 'ingestor-cli/run index --index game --backend clickhouse --since 0 --all'
     ```

   - Backfill historical data (this will take days/weeks for millions of games)
   - Monitor progress, handle errors, resume capability

4. **Verify Data Integrity**
   - Compare counts: `SELECT COUNT(*) FROM lichess.game` vs ES count
   - Sample verification: Random game IDs, verify all fields match
   - Run query equivalence tests on real data

**Deliverable**: All game data indexed in ClickHouse, counts match ES

---

### Phase 4: Dual-Write (Weeks 7-8)
**Goal**: Write to both ES and ClickHouse in production

**Tasks**:
1. **Implement Dual-Write**
   ```scala
   class DualBackend(es: ESBackend, ch: CHBackend) extends SearchBackend[IO]:
     def index[A: Encoder](index: String, id: String, doc: A): IO[Unit] =
       es.index(index, id, doc).both(ch.index(index, id, doc))
         .handleErrorWith: err =>
           // Log error but don't fail if one backend fails
           logger.error(s"Dual-write error: $err") *> IO.unit

     def search[A: Decoder](index: String, query: Query): IO[SearchResults[A]] =
       es.search(index, query)  // Still read from ES
   ```

2. **Deploy to Production**
   - Update `ingestor-app` to use `DualBackend`
   - Monitor for errors (CH write failures, discrepancies)
   - Alert on CH lag (if CH writes fall behind ES)

3. **Reconciliation Jobs**
   - Periodic job to find discrepancies between ES and CH
   - Compare counts, sample random IDs
   - Re-index if mismatches found

**Deliverable**: Production writes to both ES and CH, zero data loss

---

### Phase 5: Shadow Reads (Weeks 9-10)
**Goal**: Validate CH query results without serving to users

**Tasks**:
1. **Implement Shadow Reads**
   ```scala
   class ShadowBackend(primary: ESBackend, shadow: CHBackend) extends SearchBackend[IO]:
     def search[A: Decoder](index: String, query: Query): IO[SearchResults[A]] =
       for
         esResults <- primary.search(index, query)
         _ <- shadow.search(index, query).attempt.flatMap:  // Don't fail if CH errors
           case Left(err) =>
             logger.error(s"Shadow read error: $err")
           case Right(chResults) =>
             compareResults(esResults, chResults)  // Log discrepancies
       yield esResults  // Always return ES results
   ```

2. **Result Comparison**
   - Compare result counts
   - Compare IDs (may differ in order for ties)
   - Log when results diverge
   - Alert if divergence > threshold (e.g., 5%)

3. **Performance Monitoring**
   - Measure CH query latency vs ES
   - Identify slow queries in CH
   - Optimize CH schema/indexes if needed (add skip indexes, adjust primary key)

**Deliverable**: High confidence in CH result accuracy, performance metrics collected

---

### Phase 6: Canary Rollout (Weeks 11-12)
**Goal**: Serve small percentage of real traffic from CH

**Tasks**:
1. **Feature Flag Implementation**
   ```scala
   class CanaryBackend(
     es: ESBackend,
     ch: CHBackend,
     canaryPercent: Int  // e.g., 5
   ) extends SearchBackend[IO]:

     def search[A: Decoder](index: String, query: Query): IO[SearchResults[A]] =
       Random.nextInt(100) < canaryPercent match
         case true => ch.search(index, query)  // Use CH
         case false => es.search(index, query)  // Use ES
   ```

2. **Gradual Rollout**
   - Week 11: 5% traffic to CH
   - Monitor error rates, latency p50/p95/p99
   - If issues: rollback to 0%, investigate
   - If stable: increase to 25%

   - Week 12: 50% → 100%
   - Monitor continuously
   - Quick rollback if any issues

3. **User-Facing Metrics**
   - Track API response times
   - Monitor error rates
   - User complaints (via support tickets)

**Deliverable**: 100% of game search traffic on ClickHouse, no production issues

---

### Phase 7: Cleanup (Week 13)
**Goal**: Decommission Elasticsearch for game index

**Tasks**:
1. **Stop Dual-Write**
   - Update `ingestor-app` to write only to CH
   - Keep ES running (read-only) for rollback capability

2. **Deprecation Period** (2 weeks)
   - ES game index kept in sync (read-only, manual re-index if needed)
   - Monitor for any issues requiring rollback
   - If stable, proceed to decommission

3. **ES Cleanup**
   - Stop game index updates
   - Delete game index from ES
   - Keep ES cluster for other indexes (forum, study, team, ublog)

4. **Code Cleanup**
   - Remove ES-specific game code (can keep for reference)
   - Update documentation
   - Update runbooks

**Deliverable**: ES no longer used for game index, CH is sole backend

---

## Doobie Integration: Key Patterns & Best Practices

### Why Doobie is Ideal for This Migration

**Perfect Alignment with Existing Stack**:
- ✅ **cats-effect IO**: Same as current codebase
- ✅ **fs2 Streams**: Already used for MongoDB change streams
- ✅ **Type-safe**: Matches Smithy4s type safety philosophy
- ✅ **Composable**: Functional composition like existing code
- ✅ **Well-maintained**: Active development, excellent documentation

### Doobie Fragment Composition Patterns

**1. Building Complex Filters**:
```scala
// Pattern: Optional filters
def buildFilters(game: Game): Fragment =
  val base = fr"1=1"  // Always true base case

  val userFilter = game.user1.fold(base)(u => arrayContains("uids", u))
  val perfFilter = termsQuery("perf", game.perf).getOrElse(base)
  val dateFilter = rangeQuery("date", game.date.min, game.date.max).getOrElse(base)

  userFilter ++ fr"AND" ++ perfFilter ++ fr"AND" ++ dateFilter
```

**2. Dynamic WHERE Clauses**:
```scala
// Pattern: Collect non-empty fragments
val filters: List[Fragment] = List(
  game.user1.map(u => fr"hasAny(uids, ARRAY[$u])"),
  game.winner.map(w => fr"winner = $w"),
  game.perf.nonEmpty.option(
    fr"perf IN (" ++ game.perf.map(p => fr"$p").intercalate(fr",") ++ fr")"
  )
).flatten

// Combine with AND
val whereClause = filters match
  case Nil => fr"1=1"
  case xs  => xs.reduce(_ ++ fr"AND" ++ _)
```

**3. Reusable Query Components**:
```scala
// Shared base query
def baseQuery(table: String): Fragment =
  fr"SELECT id FROM" ++ Fragment.const(table)

// Add filters
def withFilters(base: Fragment, filters: Fragment): Fragment =
  base ++ fr"WHERE" ++ filters

// Add pagination
def paginate(query: Fragment, from: From, size: Size): Fragment =
  query ++ fr"LIMIT ${size.value} OFFSET ${from.value}"

// Compose
val fullQuery = baseQuery("lichess.game")
  .pipe(withFilters(_, buildFilters(game)))
  .pipe(paginate(_, from, size))
```

### Doobie Type Mappings for ClickHouse

**Custom Meta Instances**:
```scala
// For ClickHouse-specific types
import doobie.Meta
import java.time.LocalDateTime

// Array[String] for uids field
given Meta[Array[String]] =
  Meta[String].timap(_.split(","))(_.mkString(","))

// LocalDateTime for ClickHouse DateTime
given Meta[LocalDateTime] =
  Meta[java.sql.Timestamp].timap(_.toLocalDateTime)(java.sql.Timestamp.valueOf)

// LowCardinality(String) - just use String
// ClickHouse JDBC driver handles this transparently
```

### Connection Pool Configuration

**HikariCP Integration** (recommended for production):
```scala
libraryDependencies += "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC5"
```

```scala
import doobie.hikari.*

object ClickHouseTransactor:
  def makePooled(config: CHConfig): Resource[IO, HikariTransactor[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](32)
      xa <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "com.clickhouse.jdbc.ClickHouseDriver",
        url = s"jdbc:clickhouse://${config.host}:${config.port}/${config.database}",
        user = config.user,
        pass = config.password,
        connectEC = ce
      )
      _ <- Resource.eval:
        xa.configure: ds =>
          IO:
            ds.setMaximumPoolSize(32)
            ds.setMinimumIdle(8)
            ds.setConnectionTimeout(30000)
            ds.setIdleTimeout(600000)
            ds.setMaxLifetime(1800000)
    yield xa
```

### Streaming Results with Doobie

**Pattern: Process large result sets without loading into memory**:
```scala
// Stream results instead of .to[List]
def streamGames(query: Game): fs2.Stream[IO, String] =
  GameCH.searchQuery(query, From(0), Size(10000))
    .stream
    .transact(xa)
    .take(10000)  // Limit to prevent memory issues

// Process in batches
streamGames(query)
  .chunkN(1000)
  .evalMap: batch =>
    processGameBatch(batch.toList)
  .compile
  .drain
```

### Error Handling with Doobie

**Pattern: Graceful degradation and retries**:
```scala
import cats.effect.std.Retry
import scala.concurrent.duration.*

def searchWithRetry(query: Game): IO[List[String]] =
  val search = GameCH.searchQuery(query, From(0), Size(20))
    .to[List]
    .transact(xa)

  search.handleErrorWith:
    case e: java.sql.SQLException if isTransient(e) =>
      logger.warn(s"Transient SQL error, retrying: ${e.getMessage}") *>
      IO.sleep(1.second) *> searchWithRetry(query)
    case e =>
      logger.error(e)("ClickHouse query failed") *>
      IO.raiseError(e)

def isTransient(e: SQLException): Boolean =
  e.getMessage.contains("timeout") ||
  e.getMessage.contains("connection")
```

### Testing Strategies

**1. Unit Tests - Query Building**:
```scala
test("buildFilters generates correct SQL"):
  val game = Game(user1 = Some("alice"), perf = List(1, 2))
  val sql = GameCH.buildFilters(game).toString

  expect(sql.contains("hasAny(uids")) &&
  expect(sql.contains("perf IN"))
```

**2. Integration Tests - Type Checking**:
```scala
import doobie.scalatest.IOChecker

test("all queries type-check against real DB"):
  val queries = List(
    GameCH.searchQuery(game1, From(0), Size(20)),
    GameCH.countQuery(game2),
    GameCH.searchQuery(game3, From(100), Size(50))
  )

  queries.traverse_(check(_))
```

**3. Property Tests - Query Correctness**:
```scala
import org.scalacheck.*

test("search returns subset of count"):
  forall { (game: Game) =>
    for
      count <- GameCH.countQuery(game).unique.transact(xa)
      results <- GameCH.searchQuery(game, From(0), Size(100))
                   .to[List].transact(xa)
    yield expect(results.length <= count)
  }
```

### Performance Optimization Tips

**1. Use `.stream` for Large Results**:
```scala
// ❌ Bad: Loads everything into memory
val allGames = sql"SELECT * FROM lichess.game".query[Game].to[List].transact(xa)

// ✅ Good: Streams results
val allGames = sql"SELECT * FROM lichess.game".query[Game].stream.transact(xa)
```

**2. Batch Updates Efficiently**:
```scala
// ❌ Bad: Individual inserts
games.traverse_(g => insertOne(g))

// ✅ Good: Single batch
Update[GameSource](insertSql).updateMany(games).transact(xa)
```

**3. Use Connection Pooling**:
```scala
// ❌ Bad: New connection per query
Transactor.fromDriverManager[IO](...).use(xa => query.transact(xa))

// ✅ Good: Reuse pooled connections
val xa = HikariTransactor.make[IO](...)  // Create once
query.transact(xa)  // Reuse many times
```

### Migration from elastic4s to Doobie

**Side-by-Side Comparison**:

| elastic4s | Doobie | Notes |
|-----------|--------|-------|
| `termQuery("field", value)` | `fr"field = $value"` | Direct SQL |
| `rangeQuery("field").gte(10).lte(20)` | `fr"field BETWEEN $10 AND $20"` | SQL range |
| `termsQuery("field", List(1,2,3))` | `fr"field IN (" ++ vals.intercalate(fr",") ++ fr")"` | SQL IN |
| `search(index).query(q).size(10)` | `fr"SELECT * FROM t WHERE ..." ++ fr"LIMIT 10"` | SQL pagination |
| `client.execute(request)` | `query.transact(xa)` | Execute query |
| `SearchRequest` | `Fragment` / `Query0[A]` | Query representation |

### Resources & Documentation

**Official Resources**:
- [Doobie Documentation](https://tpolecat.github.io/doobie/)
- [Doobie Book of Knowledge](https://tpolecat.github.io/doobie/docs/01-Introduction.html)
- [ClickHouse JDBC Driver](https://github.com/ClickHouse/clickhouse-java)

**Key Concepts to Master**:
1. **Fragment composition**: Building queries incrementally
2. **Meta type classes**: Type mapping between Scala and SQL
3. **Read/Write instances**: Encoding/decoding rows
4. **Transactor**: Connection management
5. **IOChecker**: Testing utilities

---

## Single-Instance Deployment: Analysis & Recommendations

### Why Single Instance is Recommended

**For the Game Index Migration**:

1. **Data Scale is Manageable**
   - Estimated game count: ~10-100 million games (depends on Lichess scale)
   - Compressed size: ~50-500 GB (ClickHouse compression ratio 10-20x)
   - Single modern server can handle this easily

2. **Query Load is Predictable**
   - Game search is not the primary traffic driver on Lichess
   - Estimated QPS: 10-100 queries per second (not 10k+)
   - Single instance can handle 1000+ QPS easily with proper indexing

3. **Operational Simplicity**
   - No ZooKeeper/ClickHouse Keeper coordination
   - No distributed consensus complexity
   - Easier debugging and troubleshooting
   - Lower infrastructure cost

4. **Dual-Write Safety Net**
   - Elasticsearch remains available during migration
   - Quick rollback if ClickHouse fails
   - Reduces urgency for HA setup

### Single Instance Architecture

```
┌─────────────────────────────────────────┐
│         MongoDB (Primary Data)          │
└────────────┬────────────────────────────┘
             │
             │ Change Streams
             ▼
┌─────────────────────────────────────────┐
│         Ingestor Service                │
│  (Dual-write during migration)          │
└────┬────────────────────────────┬───────┘
     │                            │
     │                            │
     ▼                            ▼
┌─────────────┐          ┌─────────────────┐
│ Elasticsearch│          │  ClickHouse     │
│  (5 indexes) │          │  (game only)    │
│              │          │                 │
│ • forum      │          │  Single Server  │
│ • study      │          │  - ReplacingMT  │
│ • team       │          │  - Local disk   │
│ • ublog      │          │  - Backups to   │
│ • game (old) │          │    S3/Backblaze │
└─────────────┘          └─────────────────┘
```

### Recommended Server Specifications

**Minimum (for testing/small deployments)**:
- CPU: 4 cores
- RAM: 16 GB
- Disk: 500 GB SSD
- Network: 1 Gbps

**Recommended (for production)**:
- CPU: 8-16 cores (modern CPU, 2.5+ GHz)
- RAM: 32-64 GB (for query cache and merge operations)
- Disk: 1-2 TB NVMe SSD (fast disk critical for ClickHouse)
- Network: 10 Gbps

**Why these specs?**:
- **CPU**: ClickHouse uses vectorized query execution, benefits from modern CPUs
- **RAM**: Holds frequently accessed data, merge buffers, query cache
- **Disk**: Fast SSD critical for merge operations and range scans
- **Network**: Fast ingestion during batch backfill

### Cost Comparison: Single Instance vs Cluster

**Single Instance**:
- 1x server: $100-300/month (AWS r6i.2xlarge, Hetzner AX101)
- 1x backup storage: $10-20/month
- **Total**: ~$150/month

**3-Node Cluster** (for comparison):
- 3x servers: $300-900/month
- 3x ZooKeeper: $30-90/month
- 3x backup storage: $30-60/month
- **Total**: ~$450/month

**Savings**: ~$300/month or $3,600/year with single instance

### High Availability Considerations

**Single Instance Availability**:
- **Expected uptime**: 99.5-99.9% (depends on provider SLA)
- **MTTR** (Mean Time To Recovery): 5-30 minutes
- **Data durability**: Dependent on backups

**Strategies to Improve Availability**:

1. **Automated Backups** (Critical!):
   ```bash
   # Cron job: hourly incremental backups
   0 * * * * clickhouse-backup create_remote && clickhouse-backup delete local --keep-last=3

   # Daily full backups
   0 2 * * * clickhouse-backup create_remote full_$(date +\%Y\%m\%d)
   ```

2. **Fast Recovery Process**:
   ```bash
   # Documented recovery procedure
   # 1. Provision new ClickHouse instance
   # 2. Restore latest backup (5-15 minutes)
   clickhouse-backup restore <backup_name>
   # 3. Update DNS/load balancer to new instance
   # 4. Resume writes (ingestor catches up from checkpoint)
   ```

3. **Monitoring & Alerting**:
   ```yaml
   # Prometheus alerts
   - alert: ClickHouseDown
     expr: up{job="clickhouse"} == 0
     for: 1m
     annotations:
       summary: "ClickHouse instance is down"

   - alert: ClickHouseDiskSpace
     expr: clickhouse_disk_free_bytes / clickhouse_disk_total_bytes < 0.2
     for: 5m
     annotations:
       summary: "ClickHouse disk space < 20%"

   - alert: ClickHouseMergesDelayed
     expr: clickhouse_background_merges_running > 10
     for: 10m
     annotations:
       summary: "Too many pending merges"
   ```

4. **Graceful Degradation**:
   - If ClickHouse fails → Automatic fallback to Elasticsearch (during dual-write phase)
   - Circuit breaker pattern in search service
   - Query timeout to prevent hanging requests

### When to Migrate to Cluster

**Indicators that clustering is needed**:

1. **Data Growth**:
   - ✅ Current: <500 GB → Single instance fine
   - ⚠️ Warning: 500 GB - 2 TB → Monitor closely
   - ❌ Critical: >2 TB → Consider sharding

2. **Query Load**:
   - ✅ Current: <100 QPS → Single instance fine
   - ⚠️ Warning: 100-1000 QPS → Vertical scaling first
   - ❌ Critical: >1000 QPS → Horizontal scaling needed

3. **Availability Requirements**:
   - ✅ Current: 99.5% okay → Single instance fine
   - ⚠️ Warning: Need 99.9% → Consider replication
   - ❌ Critical: Need 99.99%+ → Must have clustering

4. **Geographic Distribution**:
   - If users in multiple continents need low latency
   - Read replicas in each region

### Migration Path: Single → Cluster

**When the time comes, migration is straightforward**:

1. **Add replication** (no downtime):
   ```sql
   -- On new replica
   CREATE TABLE lichess.game AS lichess.game_local
   ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/game', 'replica1', _version)
   ...

   -- Sync data from primary
   ALTER TABLE lichess.game FETCH PARTITION tuple() FROM '/clickhouse/tables/game'
   ```

2. **Add load balancing**:
   - Use ClickHouse proxy (chproxy) or HAProxy
   - Round-robin or least-connections

3. **Add sharding** (if needed for data size):
   - Shard by game ID hash or date ranges
   - Distributed table for queries

**DDL Change**:
```sql
-- Single instance (current)
ENGINE = ReplacingMergeTree(_version)

-- Replicated (future)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/game', '{replica}', _version)

-- Application code: NO CHANGES NEEDED
-- Queries work identically on both
```

### Backup & Disaster Recovery

**Backup Strategy** (Essential for single instance):

```yaml
# clickhouse-backup config (/etc/clickhouse-backup/config.yml)
general:
  remote_storage: s3  # or gcs, azure, ftp
  backups_to_keep_local: 3
  backups_to_keep_remote: 30

clickhouse:
  username: default
  password: ${CLICKHOUSE_PASSWORD}
  host: localhost
  port: 9000

s3:
  access_key: ${AWS_ACCESS_KEY}
  secret_key: ${AWS_SECRET_KEY}
  bucket: lichess-clickhouse-backups
  region: us-east-1
  path: game-index/
  compression_level: 9
```

**Backup Schedule**:
- **Incremental**: Every hour (fast, small)
- **Full**: Daily at 2 AM UTC
- **Retention**: 3 local, 30 remote
- **Testing**: Monthly restore test

**Recovery Time Objectives**:
- **RTO** (Recovery Time Objective): 15 minutes
- **RPO** (Recovery Point Objective): 1 hour (worst case)

**Disaster Recovery Procedure**:
```bash
# 1. Provision new ClickHouse server (or restart existing)
# 2. Install clickhouse-backup
# 3. Configure S3 credentials
# 4. List available backups
clickhouse-backup list remote

# 5. Restore latest backup
clickhouse-backup restore_remote <latest_backup>

# 6. Verify data
clickhouse-client --query "SELECT COUNT(*) FROM lichess.game"

# 7. Update DNS/configuration to point to new instance
# 8. Resume ingestor (it will catch up from last checkpoint)
```

### Performance Expectations

**Single Instance Benchmarks** (based on typical hardware):

**Query Performance**:
- Simple filters (user, date): 10-50ms
- Complex aggregations: 50-200ms
- Full table scan: 1-5 seconds (with 100M rows)

**Ingestion Performance**:
- Batch insert (1000 games): 50-100ms
- Sustained throughput: 10,000-50,000 games/sec
- Merge overhead: Automatic background, not visible

**Storage Efficiency**:
- Raw game data: ~5 KB/game average
- ClickHouse compressed: ~500 bytes/game (10x compression)
- 100M games: ~50 GB compressed

### Summary: Single Instance Decision Matrix

| Factor | Single Instance | Clustered | Recommendation |
|--------|----------------|-----------|----------------|
| **Initial Setup** | ✅ Simple | ❌ Complex | Single |
| **Operational Cost** | ✅ Low ($150/mo) | ❌ High ($450/mo) | Single |
| **Operational Complexity** | ✅ Simple | ❌ Complex | Single |
| **Performance** | ✅ Excellent | ✅ Excellent | Either |
| **Availability** | ⚠️ 99.5-99.9% | ✅ 99.9-99.99% | Single (for now) |
| **Data Durability** | ✅ With backups | ✅ With replication | Single (with backups) |
| **Scalability** | ⚠️ Vertical only | ✅ Horizontal | Single (sufficient) |
| **Migration Path** | ✅ Easy to cluster later | N/A | Single |

**Recommendation**: **Start with single instance**, monitor metrics, add clustering when (if) needed.

---

## Risk Mitigation

### Risk 1: Data Loss During Migration
**Probability**: Low
**Impact**: Critical
**Mitigation**:
- Dual-write ensures both systems have data
- Reconciliation jobs detect and fix discrepancies
- Keep ES running during migration for rollback
- Extensive testing in staging before production

### Risk 2: Query Performance Regression
**Probability**: Medium
**Impact**: High
**Mitigation**:
- Shadow reads validate performance before serving traffic
- Canary rollout allows quick rollback if issues
- Optimize CH schema based on query patterns (skip indexes, primary key tuning)
- Load testing with production query patterns

### Risk 3: Single-Instance Availability
**Probability**: Low-Medium
**Impact**: Medium (during migration), High (post-migration)
**Mitigation**:
- **During dual-write phase**: Automatic fallback to ES if CH fails (zero user impact)
- **Automated backups**: Hourly incremental + daily full to S3
- **Fast recovery**: Documented 15-minute restore procedure
- **Monitoring**: Prometheus alerts on instance health, disk space, query errors
- **Circuit breaker**: Prevent cascading failures in search service
- **Keep ES running**: During migration for instant rollback
- **Team training**: ClickHouse operations, backup/restore procedures

### Risk 4: ClickHouse Operational Complexity
**Probability**: Low (single instance is simple)
**Impact**: Medium
**Mitigation**:
- Team training on CH operations before migration
- Runbooks for common issues (OOM, disk full, merge storms)
- Monitoring and alerting from day 1
- Simple architecture (no ZooKeeper, no distributed queries)
- Community resources (ClickHouse Slack, GitHub discussions)

### Risk 5: Schema Evolution Challenges
**Probability**: Low
**Impact**: Medium
**Mitigation**:
- Plan schema carefully upfront
- Use ClickHouse lightweight updates (add columns without downtime)
- For major changes: create new table, migrate data, swap atomically
- Document schema change process

### Risk 6: Dual-Write Consistency Issues
**Probability**: Medium
**Impact**: Medium
**Mitigation**:
- Idempotent writes (ReplacingMergeTree handles duplicates)
- Reconciliation jobs detect divergence
- Monitor lag between ES and CH writes
- Retry logic for failed writes

## Success Metrics

### Performance
- **Query Latency**: p95 < 100ms (down from current ES p95)
- **Complex Queries**: Aggregations 10x faster than ES
- **Indexing Throughput**: > 10k games/second during batch indexing

### Reliability
- **Uptime**: 99.9% availability
- **Data Accuracy**: 100% consistency between ES and CH during dual-write
- **Zero Data Loss**: All game updates reflected in CH

### Operational
- **Rollback Time**: < 5 minutes (feature flag flip)
- **Deployment**: Zero-downtime migrations
- **Cost**: 30-50% reduction in infrastructure costs (CH vs ES)

## Open Questions & Next Steps

### Questions for Further Investigation

1. **Data Volume**: What's the current game count? Growth rate?
   - Affects partitioning strategy, cluster sizing
   - **Action**: Query MongoDB for counts, analyze growth trends

2. **Update Patterns**: How often are games updated after initial insert?
   - Analysis added later? Tags modified?
   - Affects ReplacingMergeTree vs other engines
   - **Action**: Analyze MongoDB oplog for update frequency

3. **Query Distribution**: What are the most common query patterns?
   - Affects primary key optimization
   - **Action**: Analyze ES query logs, identify hot queries

4. **Infrastructure**: Existing ClickHouse expertise? Cluster available?
   - Affects timeline and training needs
   - **Action**: Discuss with ops team

5. **API Changes**: Can we modify the API or must it stay backward compatible?
   - Affects query translation complexity
   - **Action**: Clarify with stakeholders

### Immediate Next Steps

1. **Explore Codebase** (1-2 days)
   - Read ingestor pipeline code in detail
   - Understand Repo implementation
   - Review test infrastructure (e2e, TestContainers)

2. **Prototype Schema Generator** (2-3 days)
   - Implement basic Smithy → DDL generation
   - Generate schema for GameSource
   - Validate with ClickHouse

3. **Prototype Query Builder** (2-3 days)
   - Implement basic query translation
   - Test with sample queries
   - Verify results match ES

4. **Decision Point**: Proceed with full implementation?
   - Review prototypes with team
   - Estimate timeline and resources
   - Get stakeholder approval

## Summary

This migration is feasible and well-suited to ClickHouse because:
- ✅ **No full-text search** required (CH's weakness)
- ✅ **Pure filtering/aggregation** workload (CH's strength)
- ✅ **Phased approach** minimizes risk
- ✅ **Dual-write strategy** enables safe rollout
- ✅ **Schema-driven** approach preserves existing patterns
- ✅ **Doobie integration** provides type-safe, composable SQL layer
- ✅ **Stack alignment** (cats-effect, fs2) - no paradigm shift needed

**Key Technology Choices**:
- **ClickHouse (Single Instance)**: OLAP database optimized for analytical queries
  - Simple deployment, low cost (~$150/month vs $450/month for cluster)
  - Sufficient for millions of games, 100+ QPS
  - Easy migration path to clustering if needed
- **Doobie**: Pure functional JDBC layer for type-safe SQL
- **ReplacingMergeTree**: Table engine for handling game updates
- **HikariCP**: Production-ready connection pooling
- **Smithy**: Schema-driven DDL generation

**Timeline**: ~13 weeks from start to ES decommission
**Risk**: Low-Medium (mitigated by dual-write and canary rollout)
**Effort**: ~1-2 engineers full-time

**Doobie Benefits**:
- Type-safe queries with compile-time checking
- Composable fragments for complex query building
- Seamless integration with existing cats-effect/fs2 stack
- Excellent testing utilities (IOChecker)
- Efficient batch operations (updateMany)
- Built-in connection pooling support

**Recommendation**: Proceed with Phase 1 (Foundation) and build prototypes to validate approach before committing to full migration.

**First Prototypes to Build**:
1. **Single-instance setup**: Docker or local ClickHouse installation
2. **Schema generator**: Smithy → ClickHouse DDL (non-replicated)
3. **Query builder**: Using Doobie fragments with type checking
4. **Batch insert pipeline**: Reuse existing `groupWithin` pattern
5. **Integration tests**: ClickHouse TestContainer with sample data
6. **Backup/restore**: Test recovery procedure

**Cost Savings with Single Instance**:
- **Infrastructure**: $300/month ($3,600/year)
- **Operational complexity**: Significantly reduced
- **Development time**: Faster initial deployment (no cluster setup)
- **Flexibility**: Easy to add clustering later if metrics indicate need
