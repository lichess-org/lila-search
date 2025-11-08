# Search service for lichess.org

> "Keep elasticsearch threads out of your web facing app, kids" -- W. Churchill

## Architecture

This project is organized into several modules:

- **app** - Search HTTP API service
- **core** - Core domain models and types
- **elastic** - Elasticsearch client wrapper
- **lila-mongo** - MongoDB repository abstractions and change stream support
- **lila-game-export** - Standalone CLI tool for exporting game data to CSV
- **ingestor-core** - Core indexing logic and repository implementations
- **ingestor-app** - Indexing service (watches MongoDB changes and updates Elasticsearch)
- **ingestor-cli** - CLI tool for batch indexing operations
- **client** - HTTP client for the search API
- **e2e** - End-to-end integration tests

## Development

### Start sbt

Start sbt:
```sh
sbt
```

### Inside sbt console

Start server:
```sh
app/run
```

Run tests:
```sh
test
```

Run code format and auto code refactor with scalafmt & scalafix:
```sh
prepare
```

Start ingestor service:
```sh
ingestor-app/run
```

Start ingestor CLI tool:
```sh
ingestor-cli/run --help
```

Start game export CLI tool:
```sh
lila-game-export/run --help
```

#### CLI Tools

##### Index commands (ingestor-cli)

```sh
# Index all documents for specific index (batch mode)
sbt 'ingestor-cli/run index --index team --since 0'

# Index all documents for all indexes (batch mode)
sbt 'ingestor-cli/run index --all --since 0'

# Index with time range
sbt 'ingestor-cli/run index --index game --since 1704067200 --until 1704153600'

# Watch mode: continuously index documents as they arrive
sbt 'ingestor-cli/run index --index game --since 1704067200 --watch'

# Watch mode for all indexes
sbt 'ingestor-cli/run index --all --since 1704067200 --watch'
```

##### Export commands (lila-game-export)

The `lila-game-export` module is a standalone CLI tool for exporting game data from MongoDB to CSV format.

```sh
# Batch export: export games from a time range to CSV
sbt 'lila-game-export/run --mongo-uri mongodb://localhost:27017 --output games.csv --since 1704067200 --until 1704153600'

# Watch mode: continuously export games as they arrive
sbt 'lila-game-export/run --mongo-uri mongodb://localhost:27017 --output games.csv --since 1704067200 --watch'

# With custom MongoDB database
sbt 'lila-game-export/run --mongo-uri mongodb://localhost:27017 --mongo-database lichess-prod --output games.csv --since 0'

# With custom batch settings
sbt 'lila-game-export/run --mongo-uri mongodb://localhost:27017 --batch-size 5000 --time-windows 10 --output games.csv --since 0'

# Using environment variables
export MONGO_URI=mongodb://localhost:27017
export MONGO_DATABASE=lichess
sbt 'lila-game-export/run --output games.csv --since 0'
```

**Available options:**
- `--mongo-uri` - MongoDB connection URI (or use `MONGO_URI` env var)
- `--mongo-database` - Database name (default: lichess, or use `MONGO_DATABASE` env var)
- `--batch-size` - Batch size for MongoDB operations (default: 1000)
- `--time-windows` - Time window in seconds for batching events (default: 5)
- `--output` - Output CSV file path (required)
- `--since` - Start timestamp in epoch seconds (required)
- `--until` - End timestamp in epoch seconds (default: now)
- `--watch` - Watch change stream continuously
- `--format` - Export format (only support csv)

### release

```bash
sbt release with-defaults
```
