# MongoDB Change Stream Buffering Strategies

**Date**: 2025-12-25 (Updated)
**Context**: Continuous MongoDB change stream with batch ingestion to ClickHouse every 1-3 hours
**Volume**: **3-4 million games per day** (Lichess production scale)
**Challenge**: How to buffer game events between receiving them from MongoDB and writing them to ClickHouse

---

## Problem Statement

**Requirements**:
- MongoDB emits game events continuously (**~40-50 games/second** for 3-4M games/day)
- ClickHouse ingestion happens in batches every 1-3 hours
- Need to buffer **146,000-437,000 games** between flushes (1-3 hour batches)
- Must handle application restarts without data loss
- Should support failure recovery
- Handle peak loads (up to **80-100 games/second** during EU evening hours)

**Scale Calculations** (based on 3.5M games/day average):

| Metric | Value |
|--------|-------|
| Games per second (average) | **~40 games/sec** |
| Games per second (peak) | **~80-100 games/sec** |
| Games per hour | **~146,000 games** |
| Games per 2-hour batch | **~292,000 games** |
| Games per 3-hour batch | **~437,000 games** |
| Buffer memory (2-hour @ 100 bytes/game) | **~29 MB** |
| Buffer memory (3-hour @ 100 bytes/game) | **~44 MB** |

**Key Questions**:
1. Where to store events between MongoDB and ClickHouse?
2. How to ensure durability (survive crashes)?
3. How to handle backpressure (if buffer fills up)?
4. How to recover after failures?
5. Can buffer handle peak loads (2.5x average rate)?

---

## Buffering Strategy Options

### Strategy 1: In-Memory Queue (fs2.Queue) ⭐ Simplest

**Architecture**:
```
MongoDB Change Stream
         ↓
   fs2.Stream[IO, GameSource]
         ↓
   Queue.bounded[IO, GameSource](500000)  # Updated for 3.5M games/day
         ↓
   Every 2 hours: drain queue → ClickHouse
```

**Implementation**:

```scala
package lila.search.ingestor

import cats.effect.*
import cats.effect.std.Queue
import fs2.Stream
import scala.concurrent.duration.*

class InMemoryBufferIngestor(
    mongoRepo: GameRepo,
    clickhouse: CHTransactor,
    bufferSize: Int = 500000  // ~3 hours capacity at 3.5M games/day
):

  // Create bounded in-memory queue
  private val buffer: IO[Queue[IO, GameSource]] =
    Queue.bounded[IO, GameSource](bufferSize)

  // Producer: MongoDB change stream → queue
  def producer(queue: Queue[IO, GameSource]): Stream[IO, Unit] =
    mongoRepo
      .watchChanges(since = Instant.EPOCH)
      .evalMap: game =>
        queue.offer(game)  // Add to queue (blocks if full)
      .handleErrorWith: err =>
        Stream.eval(IO.println(s"Producer error: $err")) >>
        Stream.sleep_(5.seconds) >>
        producer(queue)  // Restart on error

  // Consumer: Drain queue every 2 hours → ClickHouse
  def consumer(queue: Queue[IO, GameSource]): Stream[IO, Unit] =
    Stream
      .awakeEvery[IO](2.hours)
      .evalMap: _ =>
        for
          // Drain all games from queue
          games <- drainQueue(queue)
          _     <- IO.println(s"Flushing ${games.size} games to ClickHouse")

          // Batch insert to ClickHouse
          _     <- insertBatch(games)

          _     <- IO.println(s"Flush complete")
        yield ()
      .handleErrorWith: err =>
        Stream.eval(IO.println(s"Consumer error: $err")) >>
        Stream.sleep_(10.seconds) >>
        consumer(queue)  // Restart on error

  // Drain all available items from queue
  private def drainQueue(queue: Queue[IO, GameSource]): IO[List[GameSource]] =
    queue.tryTakeN(None)  // Take all available

  // Batch insert to ClickHouse
  private def insertBatch(games: List[GameSource]): IO[Unit] =
    if games.isEmpty then IO.unit
    else
      val sql = """
        INSERT INTO lichess.game
        (id, status, turns, rated, perf, uids, winner, loser, ...)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ...)
      """

      Update[GameSource](sql)
        .updateMany(games)
        .transact(clickhouse.xa)
        .void

  // Main application: Run producer and consumer concurrently
  def run: IO[Unit] =
    buffer.flatMap: queue =>
      Stream(
        producer(queue),
        consumer(queue)
      )
      .parJoinUnbounded
      .compile
      .drain
```

**Pros**:
- ✅ **Extremely simple**: ~50 lines of code
- ✅ **Fast**: No serialization, no external dependencies
- ✅ **Low latency**: Memory access only
- ✅ **Type-safe**: Scala types preserved
- ✅ **Backpressure**: Bounded queue blocks producer if full

**Cons**:
- ❌ **Not durable**: Lost on application crash/restart
- ❌ **Single instance only**: Can't scale horizontally
- ❌ **No visibility**: Can't inspect buffer externally
- ❌ **Fixed capacity**: Must size buffer for max expected load

**Failure Scenarios**:

1. **Application crashes**:
   - Data loss: 0-2 hours of games (146,000-292,000 games)
   - Recovery: Restart application, resume from last checkpoint
   - Mitigation: Use checkpointing (see below)

2. **Buffer overflow** (>500,000 games):
   - Producer blocks (backpressure)
   - No new games accepted until consumer flushes
   - Risk: MongoDB change stream may disconnect

3. **ClickHouse unavailable**:
   - Consumer retries with exponential backoff
   - Buffer continues filling
   - Eventually fills up and blocks producer

**Resource Requirements**:

| Resource | Usage |
|----------|-------|
| Memory | ~50 MB (500K games × 100 bytes/game) |
| CPU | <0.1% (idle, just queue operations) |
| Disk | 0 (pure in-memory) |

**When to Use**:
- ✅ Simple deployments (single instance)
- ✅ Search index (0-2 hour data loss acceptable)
- ✅ Low operational complexity preferred
- ✅ Want to minimize dependencies

**Recommended for Lichess**: **YES** ✅ (with checkpointing)

---

### Strategy 2: Redis Buffer (Durable)

**Architecture**:
```
MongoDB Change Stream
         ↓
   fs2.Stream[IO, GameSource]
         ↓
   Redis List (LPUSH)
         ↓
   Every 2 hours: LRANGE + DEL → ClickHouse
```

**Implementation**:

```scala
package lila.search.ingestor

import cats.effect.*
import dev.profunktor.redis4cats.*
import dev.profunktor.redis4cats.effect.Log.Stdout.given
import io.circe.syntax.*
import fs2.Stream
import scala.concurrent.duration.*

class RedisBufferIngestor(
    mongoRepo: GameRepo,
    redis: RedisCommands[IO, String, String],
    clickhouse: CHTransactor
):

  private val bufferKey = "game_buffer"

  // Producer: MongoDB → Redis
  def producer: Stream[IO, Unit] =
    mongoRepo
      .watchChanges(since = Instant.EPOCH)
      .evalMap: game =>
        // Serialize to JSON and push to Redis list
        val json = game.asJson.noSpaces
        redis.lPush(bufferKey, json)
      .handleErrorWith: err =>
        Stream.eval(IO.println(s"Producer error: $err")) >>
        Stream.sleep_(5.seconds) >>
        producer

  // Consumer: Redis → ClickHouse every 2 hours
  def consumer: Stream[IO, Unit] =
    Stream
      .awakeEvery[IO](2.hours)
      .evalMap: _ =>
        for
          // Get current buffer size
          size <- redis.lLen(bufferKey)
          _    <- IO.println(s"Buffer size: $size games")

          // Get all games from Redis (LRANGE 0 -1)
          jsons <- redis.lRange(bufferKey, 0, -1)

          // Parse JSON to GameSource
          games <- jsons.traverse: json =>
            IO.fromEither(io.circe.parser.decode[GameSource](json))

          // Insert to ClickHouse
          _ <- insertBatch(games)

          // Clear buffer (atomic operation)
          _ <- redis.del(bufferKey)

          _ <- IO.println(s"Flushed ${games.size} games")
        yield ()
      .handleErrorWith: err =>
        Stream.eval(IO.println(s"Consumer error: $err")) >>
        Stream.sleep_(10.seconds) >>
        consumer

  // Batch insert (same as in-memory version)
  private def insertBatch(games: List[GameSource]): IO[Unit] =
    if games.isEmpty then IO.unit
    else
      val sql = """
        INSERT INTO lichess.game
        (id, status, turns, rated, perf, uids, winner, loser, ...)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ...)
      """

      Update[GameSource](sql)
        .updateMany(games)
        .transact(clickhouse.xa)
        .void

  // Main application
  def run: IO[Unit] =
    Stream(producer, consumer)
      .parJoinUnbounded
      .compile
      .drain
```

**Redis Configuration**:

```conf
# redis.conf

# Persistence (RDB snapshots)
save 900 1        # Save if 1 key changed in 15 min
save 300 10       # Save if 10 keys changed in 5 min
save 60 10000     # Save if 10k keys changed in 1 min

# Append-only file (AOF) for durability
appendonly yes
appendfsync everysec  # Fsync every second (good balance)

# Memory limits
maxmemory 2gb
maxmemory-policy allkeys-lru  # Evict oldest if full
```

**Pros**:
- ✅ **Durable**: Survives application crashes (with AOF)
- ✅ **Shared buffer**: Multiple ingestor instances can use same buffer
- ✅ **Observable**: Can inspect buffer size externally
- ✅ **Flexible**: Can manually drain or replay buffer
- ✅ **Battle-tested**: Redis is very stable

**Cons**:
- ❌ **External dependency**: Requires Redis infrastructure
- ❌ **Network overhead**: Serialization + network per game
- ❌ **Serialization cost**: JSON encoding/decoding
- ❌ **More complex**: Additional moving part to monitor
- ❌ **Single point of failure**: If Redis dies, ingestion stops

**Failure Scenarios**:

1. **Application crashes**:
   - Data loss: 0 (games persisted in Redis)
   - Recovery: Restart application, continue from Redis buffer

2. **Redis crashes**:
   - Data loss: 0-1 second (with appendfsync everysec)
   - Recovery: Redis restarts from AOF, application continues

3. **Network partition**:
   - Producer can't write to Redis
   - Buffer in application memory until reconnection
   - Risk: OOM if partition is long

**Resource Requirements**:

| Resource | Redis Usage | Application Usage |
|----------|-------------|-------------------|
| Memory | ~70 MB (500K games × 130 bytes JSON) | ~10 MB |
| CPU | <1% (Redis) | <0.5% (serialization) |
| Disk | ~70 MB (AOF) | 0 |
| Network | ~5 KB/s (40 games/sec × 130 bytes) | Same |

**When to Use**:
- ✅ Need durability (can't lose data on crash)
- ✅ Multiple ingestor instances (HA)
- ✅ Want to monitor buffer externally
- ✅ Already have Redis in infrastructure
- ❌ Don't want external dependencies

**Recommended for Lichess**: **Maybe** (if need HA)

---

### Strategy 3: Kafka/Event Streaming (Enterprise)

**Architecture**:
```
MongoDB Change Stream
         ↓
   Kafka Producer
         ↓
   Kafka Topic: "game-events"
   (partitions: 12, replication: 3)
         ↓
   Kafka Consumer (every 2 hours)
         ↓
   ClickHouse Batch Insert
```

**Implementation**:

```scala
package lila.search.ingestor

import cats.effect.*
import fs2.Stream
import fs2.kafka.*
import io.circe.syntax.*
import scala.concurrent.duration.*

class KafkaBufferIngestor(
    mongoRepo: GameRepo,
    clickhouse: CHTransactor
):

  // Kafka producer settings
  private val producerSettings =
    ProducerSettings[IO, String, String]
      .withBootstrapServers("localhost:9092")
      .withAcks(Acks.All)  // Wait for all replicas
      .withEnableIdempotence(true)
      .withRetries(Int.MaxValue)

  // Kafka consumer settings
  private val consumerSettings =
    ConsumerSettings[IO, String, String]
      .withBootstrapServers("localhost:9092")
      .withGroupId("clickhouse-ingestor")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withEnableAutoCommit(false)

  // Producer: MongoDB → Kafka
  def producer: Stream[IO, Unit] =
    KafkaProducer
      .stream(producerSettings)
      .flatMap: producer =>
        mongoRepo
          .watchChanges(since = Instant.EPOCH)
          .map: game =>
            val key = game.id  // Partition by game ID
            val value = game.asJson.noSpaces
            ProducerRecord("game-events", key, value)
          .evalMap: record =>
            producer.produce(ProducerRecords.one(record)).flatten
          .map(_.offset)

  // Consumer: Kafka → ClickHouse (batch every 2 hours)
  def consumer: Stream[IO, Unit] =
    KafkaConsumer
      .stream(consumerSettings)
      .subscribeTo("game-events")
      .flatMap: consumer =>
        // Collect records for 2 hours
        consumer.stream
          .groupWithin(100000, 2.hours)  // Max 100k or 2 hours
          .evalMap: batch =>
            for
              // Parse records
              games <- batch.toList.traverse: record =>
                IO.fromEither(io.circe.parser.decode[GameSource](record.record.value))

              // Insert to ClickHouse
              _ <- insertBatch(games)

              // Commit offsets
              _ <- batch.map(_.offset).foldMap(CommittableOffsetBatch.apply).commit

              _ <- IO.println(s"Flushed ${games.size} games")
            yield ()

  // Batch insert (same as before)
  private def insertBatch(games: List[GameSource]): IO[Unit] =
    if games.isEmpty then IO.unit
    else
      val sql = """
        INSERT INTO lichess.game
        (id, status, turns, rated, perf, uids, winner, loser, ...)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ...)
      """

      Update[GameSource](sql)
        .updateMany(games)
        .transact(clickhouse.xa)
        .void

  // Main application
  def run: IO[Unit] =
    Stream(producer, consumer)
      .parJoinUnbounded
      .compile
      .drain
```

**Kafka Topic Configuration**:

```bash
# Create topic with 12 partitions, replication factor 3
kafka-topics.sh --create \
  --topic game-events \
  --partitions 12 \
  --replication-factor 3 \
  --config retention.ms=86400000 \  # 24 hours retention
  --config segment.ms=3600000 \     # 1 hour segments
  --config compression.type=zstd    # Compress messages
```

**Pros**:
- ✅ **Highly durable**: Replicated across multiple brokers
- ✅ **Scalable**: Horizontal scaling via partitions
- ✅ **Replay capability**: Can re-process historical data
- ✅ **Multiple consumers**: Can feed multiple systems
- ✅ **Exactly-once semantics**: With idempotent producer + transactional consumer
- ✅ **Monitoring**: Rich ecosystem (Kafka UI, metrics)

**Cons**:
- ❌ **Heavy infrastructure**: Requires Kafka cluster (3+ brokers + ZooKeeper/KRaft)
- ❌ **Operational complexity**: Need Kafka expertise
- ❌ **Higher latency**: Network + broker overhead (~5-20ms)
- ❌ **Resource intensive**: Each broker needs 4+ GB RAM
- ❌ **Overkill for simple use case**: Too much for single data flow

**Failure Scenarios**:

1. **Application crashes**:
   - Data loss: 0 (messages in Kafka)
   - Recovery: Restart, resume from last committed offset

2. **Kafka broker crashes**:
   - Data loss: 0 (replicated to other brokers)
   - Impact: Transparent failover (< 1 second)

3. **Multiple broker failures**:
   - Data loss: 0 (if < replication factor brokers fail)
   - Impact: Kafka continues operating

**Resource Requirements**:

| Component | Memory | CPU | Disk | Count |
|-----------|--------|-----|------|-------|
| Kafka broker | 4-8 GB | 2 cores | 100 GB | 3 |
| ZooKeeper | 1-2 GB | 1 core | 10 GB | 3 |
| Producer app | 1 GB | 0.5 core | 0 | 1 |
| Consumer app | 2 GB | 1 core | 0 | 1 |
| **Total** | **20-34 GB** | **11 cores** | **320 GB** | **8 instances** |

**When to Use**:
- ✅ Already have Kafka in infrastructure
- ✅ Need to feed multiple downstream systems
- ✅ Need replay capability for reprocessing
- ✅ Enterprise-scale deployment
- ❌ Simple single data flow (overkill)

**Recommended for Lichess**: **NO** (overkill for this use case)

---

### Strategy 4: File-Based Buffer (Local Disk)

**Architecture**:
```
MongoDB Change Stream
         ↓
   Append to hourly file
   /var/buffer/games-2024-12-25-14.jsonl
         ↓
   Every 2 hours: Read + Delete files → ClickHouse
```

**Implementation**:

```scala
package lila.search.ingestor

import cats.effect.*
import fs2.Stream
import fs2.io.file.*
import io.circe.syntax.*
import scala.concurrent.duration.*
import java.nio.file.Paths

class FileBufferIngestor(
    mongoRepo: GameRepo,
    clickhouse: CHTransactor,
    bufferDir: Path = Paths.get("/var/buffer/games")
):

  // Producer: MongoDB → JSONL files (one per hour)
  def producer: Stream[IO, Unit] =
    mongoRepo
      .watchChanges(since = Instant.EPOCH)
      .groupWithin(1000, 10.seconds)  // Batch writes (1000 games or 10 sec)
      .evalMap: batch =>
        val timestamp = java.time.Instant.now()
        val hour = timestamp.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val filename = s"games-${hour}.jsonl"
        val filepath = bufferDir.resolve(filename)

        // Append batch to file
        val jsonLines = batch.map(game => game.asJson.noSpaces + "\n").mkString

        Files[IO]
          .createDirectories(bufferDir)
          .flatMap: _ =>
            Stream
              .emit(jsonLines)
              .through(fs2.text.utf8.encode)
              .through(Files[IO].writeAll(filepath, Flags.Append))
              .compile
              .drain

  // Consumer: Read files every 2 hours → ClickHouse
  def consumer: Stream[IO, Unit] =
    Stream
      .awakeEvery[IO](2.hours)
      .evalMap: _ =>
        for
          // List all .jsonl files in buffer directory
          files <- Files[IO]
            .list(bufferDir)
            .filter(_.toString.endsWith(".jsonl"))
            .compile
            .toList

          _ <- IO.println(s"Processing ${files.size} buffer files")

          // Process each file
          _ <- files.traverse_: file =>
            for
              // Read file, parse JSON lines
              games <- Files[IO]
                .readAll(file)
                .through(fs2.text.utf8.decode)
                .through(fs2.text.lines)
                .filter(_.nonEmpty)
                .evalMap: line =>
                  IO.fromEither(io.circe.parser.decode[GameSource](line))
                .compile
                .toList

              // Insert to ClickHouse
              _ <- insertBatch(games)

              // Delete file
              _ <- Files[IO].delete(file)

              _ <- IO.println(s"Processed ${file.fileName}: ${games.size} games")
            yield ()

          _ <- IO.println(s"Flush complete")
        yield ()

  // Batch insert (same as before)
  private def insertBatch(games: List[GameSource]): IO[Unit] =
    if games.isEmpty then IO.unit
    else
      val sql = """
        INSERT INTO lichess.game
        (id, status, turns, rated, perf, uids, winner, loser, ...)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ...)
      """

      Update[GameSource](sql)
        .updateMany(games)
        .transact(clickhouse.xa)
        .void

  // Main application
  def run: IO[Unit] =
    Stream(producer, consumer)
      .parJoinUnbounded
      .compile
      .drain
```

**File Format** (JSONL - JSON Lines):
```json
{"id":"abc123","status":30,"turns":42,"rated":true,...}
{"id":"def456","status":31,"turns":38,"rated":false,...}
{"id":"ghi789","status":30,"turns":55,"rated":true,...}
```

**Pros**:
- ✅ **Durable**: Survives application crashes
- ✅ **No external dependencies**: Just local filesystem
- ✅ **Simple**: Easy to understand and debug
- ✅ **Inspectable**: Can manually read/edit buffer files
- ✅ **Replay friendly**: Keep files for reprocessing
- ✅ **Cheap**: Local disk cheaper than Redis/Kafka

**Cons**:
- ❌ **Single instance only**: File locks prevent multiple writers
- ❌ **Disk I/O**: Slower than memory (but still fast on SSD)
- ❌ **No backpressure**: Disk can fill up if writes > reads
- ❌ **Manual cleanup**: Need to handle old files
- ❌ **Crash recovery**: Need to handle partial files

**Failure Scenarios**:

1. **Application crashes**:
   - Data loss: 0-10 seconds (buffered writes)
   - Recovery: Restart, read unprocessed files

2. **Disk full**:
   - Producer can't write new files
   - Application should alert and stop
   - Recovery: Delete old files or add disk space

3. **Partial file write**:
   - Last file may be incomplete
   - Need to validate files on startup
   - Skip or retry incomplete records

**Resource Requirements**:

| Resource | Usage |
|----------|-------|
| Disk | ~60 MB per hour (146K games × 400 bytes JSONL) |
| Memory | ~50 MB (file buffers) |
| CPU | <1% (file I/O) |
| IOPS | ~150 writes/sec (batched) |

**When to Use**:
- ✅ Want durability without external dependencies
- ✅ Single instance deployment
- ✅ Need to inspect/replay buffer manually
- ✅ Have fast local SSD
- ❌ Need high availability (multi-instance)

**Recommended for Lichess**: **Maybe** (good middle ground)

---

### Strategy 5: ClickHouse Staging Table (Database-Native)

**Architecture**:
```
MongoDB Change Stream
         ↓
   INSERT to lichess.game_staging
   (continuous, small batches)
         ↓
   Every 2 hours: INSERT INTO lichess.game SELECT * FROM staging
         ↓
   DROP staging partition
```

**Implementation**:

```scala
package lila.search.ingestor

import cats.effect.*
import doobie.*
import doobie.implicits.*
import fs2.Stream
import scala.concurrent.duration.*

class StagingTableIngestor(
    mongoRepo: GameRepo,
    clickhouse: Transactor[IO]
):

  // Producer: MongoDB → staging table (continuous micro-batches)
  def producer: Stream[IO, Unit] =
    mongoRepo
      .watchChanges(since = Instant.EPOCH)
      .groupWithin(1000, 10.seconds)  // Micro-batches: 1000 games or 10 sec
      .evalMap: batch =>
        val sql = """
          INSERT INTO lichess.game_staging
          (id, status, turns, rated, perf, uids, winner, loser, ..., ingested_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ..., now())
        """

        Update[GameSource](sql)
          .updateMany(batch.toList)
          .transact(clickhouse)
          .void
      .handleErrorWith: err =>
        Stream.eval(IO.println(s"Producer error: $err")) >>
        Stream.sleep_(5.seconds) >>
        producer

  // Consumer: staging → main table every 2 hours
  def consumer: Stream[IO, Unit] =
    Stream
      .awakeEvery[IO](2.hours)
      .evalMap: _ =>
        for
          // Calculate partition to move (2 hours ago)
          twoHoursAgo <- IO.realTimeInstant.map(_.minusSeconds(2 * 3600))
          partition = toStartOfHour(twoHoursAgo)

          // Move data from staging to main
          _ <- sql"""
            INSERT INTO lichess.game
            SELECT id, status, turns, rated, perf, uids, winner, loser, ...
            FROM lichess.game_staging
            WHERE toStartOfHour(ingested_at) = $partition
          """.update.run.transact(clickhouse)

          // Get count
          count <- sql"""
            SELECT COUNT(*)
            FROM lichess.game_staging
            WHERE toStartOfHour(ingested_at) = $partition
          """.query[Long].unique.transact(clickhouse)

          // Drop staging partition
          _ <- sql"""
            ALTER TABLE lichess.game_staging
            DROP PARTITION ${toYYYYMMDDHH(partition)}
          """.update.run.transact(clickhouse)

          _ <- IO.println(s"Moved $count games from staging to main table")
        yield ()
      .handleErrorWith: err =>
        Stream.eval(IO.println(s"Consumer error: $err")) >>
        Stream.sleep_(10.seconds) >>
        consumer

  private def toStartOfHour(instant: java.time.Instant): String =
    instant.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString

  private def toYYYYMMDDHH(timestamp: String): String =
    // Convert "2024-12-25T14:00:00Z" to "2024122514"
    timestamp.take(13).replaceAll("[^0-9]", "")

  // Main application
  def run: IO[Unit] =
    Stream(producer, consumer)
      .parJoinUnbounded
      .compile
      .drain
```

**Schema**:

```sql
-- Staging table (temporary storage)
CREATE TABLE lichess.game_staging
(
    -- Same schema as main table
    id String,
    status UInt8,
    turns UInt16,
    rated UInt8,
    perf UInt8,
    uids Array(String),
    winner LowCardinality(String),
    loser LowCardinality(String),
    winnerColor UInt8,
    whiteUser LowCardinality(String),
    blackUser LowCardinality(String),
    averageRating Nullable(UInt16),
    ai Nullable(UInt8),
    date DateTime,
    duration Nullable(UInt32),
    clockInit Nullable(UInt32),
    clockInc Nullable(UInt16),
    analysed UInt8,
    source Nullable(UInt8),

    -- Staging metadata
    ingested_at DateTime DEFAULT now()
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDDHH(ingested_at)  -- Hourly partitions
ORDER BY (id)
SETTINGS index_granularity = 8192;

-- Main table (same as before)
CREATE TABLE lichess.game
(
    -- Same fields (without ingested_at)
    ...
)
ENGINE = ReplacingMergeTree(_version)
PARTITION BY toYYYYMM(date)
ORDER BY (perf, date, id)
SETTINGS index_granularity = 8192;
```

**Pros**:
- ✅ **Fully durable**: Data persisted in ClickHouse
- ✅ **No external dependencies**: Just ClickHouse
- ✅ **Queryable buffer**: Can inspect staging data
- ✅ **Atomic moves**: INSERT SELECT is transactional
- ✅ **Automatic cleanup**: DROP PARTITION removes old data
- ✅ **Compression**: Staging data compressed with LZ4

**Cons**:
- ❌ **Double writes**: Write to staging, then move to main (2x I/O)
- ❌ **Disk overhead**: Staging + main tables consume disk
- ❌ **Merge pressure**: Both staging and main tables trigger merges
- ❌ **Schema duplication**: Must keep schemas in sync
- ❌ **Complex queries**: Need to query both tables for recent data

**Failure Scenarios**:

1. **Application crashes**:
   - Data loss: 0 (data in staging table)
   - Recovery: Restart, continue processing staging partitions

2. **ClickHouse crashes**:
   - Data loss: 0 (replicated if using ReplicatedMergeTree)
   - Recovery: ClickHouse restarts, staging data intact

3. **Consumer fails mid-move**:
   - Partial data in main table
   - Solution: Use ReplacingMergeTree (handles duplicates)
   - Or: Use transactions (ClickHouse 23.0+)

**Resource Requirements**:

| Resource | Staging Table | Main Table | Total |
|----------|--------------|------------|-------|
| Disk | ~30 GB | ~110 GB | ~140 GB |
| Memory | ~2 GB | ~4 GB | ~6 GB |
| CPU (merges) | 5% | 10% | 15% |

**When to Use**:
- ✅ Want durability without external dependencies
- ✅ Need to query recent data before batch
- ✅ Already using ClickHouse for everything
- ❌ Minimizing disk usage is critical

**Recommended for Lichess**: **Maybe** (clean but higher resource use)

---

## Strategy Comparison Matrix

| Factor | In-Memory | Redis | Kafka | File-Based | CH Staging |
|--------|-----------|-------|-------|------------|------------|
| **Durability** | ❌ | ✅ | ✅✅ | ✅ | ✅ |
| **Complexity** | ✅✅ Simple | ⚠️ Medium | ❌ Complex | ⚠️ Medium | ⚠️ Medium |
| **External Deps** | ✅ None | ❌ Redis | ❌ Kafka | ✅ None | ✅ None |
| **Scalability** | ❌ Single | ✅ Multi | ✅✅ Multi | ❌ Single | ⚠️ Single |
| **Resource Usage** | ✅✅ 50 MB | ⚠️ 70 MB + Redis | ❌ 20 GB+ | ⚠️ 60 MB/hr | ❌ +30 GB |
| **Operational Cost** | ✅ Low | ⚠️ Medium | ❌ High | ⚠️ Medium | ⚠️ Medium |
| **Observability** | ❌ | ✅ | ✅✅ | ⚠️ | ✅ |
| **Recovery Time** | ⚠️ 0-2hr loss | ✅ Instant | ✅ Instant | ✅ Instant | ✅ Instant |
| **Data Loss Risk** | ⚠️ On crash | ✅ None | ✅ None | ✅ Minimal | ✅ None |

---

## Recommended Strategy: Hybrid Approach

**Best Solution**: **In-Memory + Checkpointing**

Combine the simplicity of in-memory buffering with durability via checkpointing.

### Architecture

```
MongoDB Change Stream
         ↓
   Track: last_processed_timestamp
         ↓
   Queue.bounded[IO, GameSource](500000)
         ↓
   Every 2 hours:
     1. Flush queue → ClickHouse
     2. Save checkpoint → ClickHouse
         ↓
   On restart: Resume from checkpoint
```

### Implementation

```scala
package lila.search.ingestor

import cats.effect.*
import cats.effect.std.Queue
import doobie.*
import doobie.implicits.*
import fs2.Stream
import scala.concurrent.duration.*

class CheckpointedIngestor(
    mongoRepo: GameRepo,
    clickhouse: Transactor[IO],
    bufferSize: Int = 500000  // ~3 hours capacity at 3.5M games/day
):

  // Checkpoint table schema
  // CREATE TABLE lichess.ingestor_checkpoint
  // (
  //     service_id String,
  //     last_processed_timestamp DateTime,
  //     last_processed_game_id String,
  //     updated_at DateTime DEFAULT now()
  // )
  // ENGINE = ReplacingMergeTree(updated_at)
  // ORDER BY (service_id);

  private val serviceId = "game-ingestor"

  // Load last checkpoint
  def loadCheckpoint(): IO[java.time.Instant] =
    sql"""
      SELECT last_processed_timestamp
      FROM lichess.ingestor_checkpoint
      WHERE service_id = $serviceId
      ORDER BY updated_at DESC
      LIMIT 1
    """
      .query[java.time.Instant]
      .option
      .transact(clickhouse)
      .map(_.getOrElse(java.time.Instant.EPOCH))

  // Save checkpoint
  def saveCheckpoint(timestamp: java.time.Instant, gameId: String): IO[Unit] =
    sql"""
      INSERT INTO lichess.ingestor_checkpoint
      (service_id, last_processed_timestamp, last_processed_game_id, updated_at)
      VALUES ($serviceId, $timestamp, $gameId, now())
    """.update.run.transact(clickhouse).void

  // Producer with checkpoint tracking
  def producer(queue: Queue[IO, (GameSource, java.time.Instant)]): Stream[IO, Unit] =
    Stream
      .eval(loadCheckpoint())
      .flatMap: checkpoint =>
        mongoRepo
          .watchChanges(since = checkpoint)
          .evalMap: game =>
            val timestamp = game.date  // Or use change stream timestamp
            queue.offer((game, timestamp))

  // Consumer with checkpoint persistence
  def consumer(queue: Queue[IO, (GameSource, java.time.Instant)]): Stream[IO, Unit] =
    Stream
      .awakeEvery[IO](2.hours)
      .evalMap: _ =>
        for
          // Drain all games from queue
          gamesWithTimestamps <- drainQueue(queue)

          games = gamesWithTimestamps.map(_._1)
          maxTimestamp = gamesWithTimestamps.map(_._2).maxOption
          lastGameId = gamesWithTimestamps.lastOption.map(_._1.id)

          _ <- IO.println(s"Flushing ${games.size} games to ClickHouse")

          // Batch insert to ClickHouse
          _ <- insertBatch(games)

          // Save checkpoint
          _ <- (maxTimestamp, lastGameId).tupled.fold(IO.unit): (ts, id) =>
            saveCheckpoint(ts, id)

          _ <- IO.println(s"Checkpoint saved: $maxTimestamp")
        yield ()

  private def drainQueue(
      queue: Queue[IO, (GameSource, java.time.Instant)]
  ): IO[List[(GameSource, java.time.Instant)]] =
    queue.tryTakeN(None)

  private def insertBatch(games: List[GameSource]): IO[Unit] =
    if games.isEmpty then IO.unit
    else
      val sql = """
        INSERT INTO lichess.game
        (id, status, turns, rated, perf, uids, winner, loser, ...)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ...)
      """

      Update[GameSource](sql)
        .updateMany(games)
        .transact(clickhouse)
        .void

  def run: IO[Unit] =
    Queue
      .bounded[IO, (GameSource, java.time.Instant)](bufferSize)
      .flatMap: queue =>
        Stream(
          producer(queue),
          consumer(queue)
        )
        .parJoinUnbounded
        .compile
        .drain
```

### Benefits

✅ **Simple**: Still ~100 lines of code
✅ **Fast**: In-memory buffering
✅ **Recoverable**: Can resume from checkpoint
✅ **No external deps**: Just ClickHouse
✅ **Acceptable data loss**: Max 2 hours (on crash before checkpoint)

### Recovery Scenarios

**Normal operation**:
```
14:00 - Start producer
16:00 - Flush 292K games, save checkpoint (timestamp: 16:00)
18:00 - Flush 292K games, save checkpoint (timestamp: 18:00)
```

**Crash at 17:30**:
```
17:30 - Application crashes (73K games in memory, lost)
17:35 - Restart application
17:35 - Load checkpoint: 16:00
17:35 - Resume from MongoDB at 16:00 (replays 1.5 hr)
18:00 - Flush 292K games (2 hours worth), save checkpoint
```

**Data loss**: 0 (MongoDB replays from checkpoint)

**Duplicate risk**: Possible (if crash during flush)
**Solution**: ReplacingMergeTree handles duplicates automatically

---

## Final Recommendation for Lichess

### Primary Recommendation: **In-Memory + Checkpointing** ⭐

**Why**:
1. ✅ **Simplest implementation** (~100 lines)
2. ✅ **No external dependencies** (just ClickHouse)
3. ✅ **Acceptable data loss** (0-2 hours on crash, recoverable from MongoDB)
4. ✅ **Low resource usage** (50 MB memory)
5. ✅ **Easy to operate** (no Redis/Kafka to manage)
6. ✅ **Perfect for search index** (2-hour lag already acceptable)

**Configuration**:
```scala
val ingestor = new CheckpointedIngestor(
  mongoRepo = mongoGameRepo,
  clickhouse = clickhouseTransactor,
  bufferSize = 500000  // ~3 hours capacity at 3.5M games/day
)
```

**Implementation Timeline**:
- Week 1: Implement in-memory buffer (2 days)
- Week 2: Add checkpointing (2 days)
- Week 3: Testing & deployment (3 days)
- Week 4: Monitor & tune (ongoing)

### Alternative: **File-Based Buffer** (If Need More Durability)

**When to use**:
- Want stronger durability guarantees
- Willing to accept file I/O overhead
- Want to inspect buffer manually
- Don't mind 60-120 MB disk per hour

**Trade-off**: +50% complexity, +1.5 GB disk/day

### Not Recommended for Lichess:

❌ **Redis**: Overkill unless already in infrastructure
❌ **Kafka**: Way too heavy for single data flow
❌ **CH Staging**: Double disk usage, more complex

---

## Monitoring & Alerting

### Key Metrics

```sql
-- Buffer lag (in-memory: estimate from last checkpoint)
SELECT
    now() - last_processed_timestamp AS lag_seconds,
    last_processed_game_id
FROM lichess.ingestor_checkpoint
WHERE service_id = 'game-ingestor'
ORDER BY updated_at DESC
LIMIT 1;

-- Ingestion rate (games/hour)
SELECT
    toStartOfHour(date) AS hour,
    COUNT(*) AS games_ingested
FROM lichess.game
WHERE date > now() - INTERVAL 24 HOUR
GROUP BY hour
ORDER BY hour DESC;

-- Checkpoint history
SELECT
    updated_at,
    last_processed_timestamp,
    updated_at - last_processed_timestamp AS processing_lag
FROM lichess.ingestor_checkpoint
WHERE service_id = 'game-ingestor'
ORDER BY updated_at DESC
LIMIT 10;
```

### Alerts

```yaml
# Prometheus/Grafana alerts

- alert: IngestionLagHigh
  expr: clickhouse_ingestion_lag_seconds > 14400  # 4 hours
  for: 30m
  severity: warning
  annotations:
    summary: "Game ingestion lagging > 4 hours"

- alert: IngestionStopped
  expr: changes(clickhouse_games_ingested_total[1h]) == 0
  for: 1h
  severity: critical
  annotations:
    summary: "No games ingested in last hour"

- alert: CheckpointStale
  expr: time() - clickhouse_last_checkpoint_timestamp > 10800  # 3 hours
  for: 30m
  severity: warning
  annotations:
    summary: "Checkpoint not updated in 3 hours"
```

---

## Appendix: Implementation Checklist

### Week 1: Core Implementation

- [ ] Implement in-memory Queue-based buffer
- [ ] Implement MongoDB change stream consumer
- [ ] Implement ClickHouse batch inserter
- [ ] Add basic error handling and retries
- [ ] Test with small dataset (100K games)

### Week 2: Checkpointing

- [ ] Create checkpoint table in ClickHouse
- [ ] Implement checkpoint save/load logic
- [ ] Test crash recovery
- [ ] Test duplicate handling (ReplacingMergeTree)
- [ ] Add checkpoint monitoring queries

### Week 3: Production Hardening

- [ ] Add comprehensive logging
- [ ] Add metrics (Prometheus)
- [ ] Implement graceful shutdown
- [ ] Add backpressure handling
- [ ] Load testing with full dataset

### Week 4: Deployment

- [ ] Deploy to staging environment
- [ ] Run parallel with existing system
- [ ] Compare data between systems
- [ ] Monitor for 1 week
- [ ] Production cutover

---

**Document Version**: 1.0
**Last Updated**: 2025-12-25
**Related Documents**:
- `clickhouse_game_storage_estimation.md` - Storage analysis
- `zstd_query_performance_impact.md` - Query performance
