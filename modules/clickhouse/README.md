# ClickHouse Module

This module provides ClickHouse connectivity for lila-search using [Doobie](https://tpolecat.github.io/doobie/), a pure functional JDBC layer for Scala.

## Quick Start

### Prerequisites

1. ClickHouse running locally (see `../../../docker/CLICKHOUSE.md`)
2. The `lichess` database and `game` table created

```bash
# From the docker directory
cd ../../../docker
docker compose --profile clickhouse up -d
./scripts/setup-clickhouse.sh
```

### Run the Connection Example

```bash
# From the lila-search root directory
sbt "clickhouse/run"
```

This will:
- Connect to ClickHouse
- Query the database version
- List databases and tables
- Query the game table
- Demonstrate connection pooling with concurrent queries

### Run the Tests

```bash
sbt "clickhouse/test"
```

The tests verify:
- Basic connectivity (ping)
- Version check
- Database and table existence
- Query execution
- Connection pooling

## Architecture

### Transactor

The `CHTransactor` object provides two ways to create a Doobie transactor:

1. **Simple Connection** (for testing):
   ```scala
   import lila.search.clickhouse.*

   val config = CHTransactor.Config(
     host = "localhost",
     port = 8123,
     database = "lichess"
   )

   CHTransactor.makeSimple(config).use { xa =>
     // Use the transactor
   }
   ```

2. **HikariCP Pooled Connection** (for production):
   ```scala
   CHTransactor.makePooled(config).use { xa =>
     // Connection pooling with:
     // - Max 16 connections
     // - Min 2 idle connections
     // - Connection validation
     // - Leak detection
   }
   ```

### Query Execution

Using Doobie's SQL interpolator and fragments:

```scala
import doobie.*
import doobie.implicits.*

// Simple query
val version: IO[String] =
  sql"SELECT version()"
    .query[String]
    .unique
    .transact(xa)

// Parameterized query
def getGame(id: String): IO[Option[GameRow]] =
  sql"SELECT id, winner, turns FROM lichess.game WHERE id = $id"
    .query[GameRow]
    .option
    .transact(xa)

// Batch insert
def insertGames(games: List[GameRow]): IO[Int] =
  Update[GameRow](
    "INSERT INTO lichess.game (id, winner, turns) VALUES (?, ?, ?)"
  ).updateMany(games)
   .transact(xa)
```

## Configuration

### Local Development

Default configuration connects to Docker ClickHouse:

```scala
CHTransactor.Config(
  host = "localhost",      // or "clickhouse" when running in Docker
  port = 8123,             // HTTP port for JDBC
  database = "lichess",
  user = "default",
  password = ""            // No password for local development
)
```

### Production

For production, use environment variables and HikariCP pooling:

```scala
val config = CHTransactor.Config(
  host = sys.env.getOrElse("CLICKHOUSE_HOST", "localhost"),
  port = sys.env.getOrElse("CLICKHOUSE_PORT", "8123").toInt,
  database = sys.env.getOrElse("CLICKHOUSE_DATABASE", "lichess"),
  user = sys.env.getOrElse("CLICKHOUSE_USER", "default"),
  password = sys.env.getOrElse("CLICKHOUSE_PASSWORD", "")
)

CHTransactor.makePooled(config).use { xa =>
  // Your application code
}
```

## JDBC URL Format

The ClickHouse JDBC URL format is:
```
jdbc:clickhouse://host:port/database
```

For local Docker:
```
jdbc:clickhouse://localhost:8123/lichess
```

For services running in Docker Compose:
```
jdbc:clickhouse://clickhouse:8123/lichess
```

## Dependencies

This module uses:
- **Doobie Core** (`1.0.0-RC5`) - Pure functional JDBC layer
- **Doobie Hikari** (`1.0.0-RC5`) - HikariCP connection pooling
- **ClickHouse JDBC** (`0.6.5`) - Official ClickHouse JDBC driver
- **Cats Effect** (`3.6.3`) - Effect system
- **fs2** (`3.12.2`) - Streaming library

## Next Steps

This module provides the foundation for:

1. **Query Builder** - Type-safe query construction using Doobie Fragments
2. **Schema Generator** - Generate ClickHouse DDL from Smithy schemas
3. **Ingestion Pipeline** - Batch insert games from MongoDB
4. **Search Service** - Query games with complex filters

See `../../../CLICKHOUSE_MIGRATION_PLAN.md` for the complete migration roadmap.

## Troubleshooting

### Connection Refused

Ensure ClickHouse is running:
```bash
curl http://localhost:8123/ping
# Should return: Ok.
```

### Database Not Found

Create the database:
```bash
docker exec -it lila_clickhouse clickhouse-client --query "CREATE DATABASE IF NOT EXISTS lichess"
```

### Table Not Found

Run the setup script:
```bash
cd ../../../docker
./scripts/setup-clickhouse.sh
```

### JDBC Driver Issues

The ClickHouse JDBC driver is automatically included. If you see class not found errors, ensure:
1. The dependency is in `build.sbt` (already configured)
2. Run `sbt update` to download dependencies

## Resources

- [Doobie Documentation](https://tpolecat.github.io/doobie/)
- [ClickHouse JDBC Driver](https://github.com/ClickHouse/clickhouse-java)
- [ClickHouse SQL Reference](https://clickhouse.com/docs/en/sql-reference)
