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

```sh
# index all documents for specific index
sbt 'ingestor-cli/run index --index team --since 0'

# index all documents for all indexes
sbt 'ingestor-cli/run index --all --since 0'
```

### release

```bash
sbt release with-defaults
```
