# Search service for lichess.org

> "Keep elasticsearch threads out of your web facing app, kids" -- W. Churchill

## Developement

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

Start ingestor cli tool
```sh
ingestor-cli/run --help
```

#### CLI tool

##### Index commands

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

##### Export commands

```sh
# Batch export: export games from a time range to CSV
sbt 'ingestor-cli/run export --index game --format csv --output games.csv --since 1704067200 --until 1704153600'

# Watch mode: continuously export games as they arrive
sbt 'ingestor-cli/run export --index game --format csv --output games.csv --since 1704067200 --watch'
```

**Note:** Currently only `game` index is supported for CSV export.

### release

```bash
sbt release with-defaults
```
