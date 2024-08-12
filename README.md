# Search service for lichess.org

> "Keep elasticsearch threads out of your web facing app, kids" -- W. Churchill

## Developement

### Start sbt

Copy default settings
```sh
cp .env.example .env
cp .sbtops.example .sbtopts
```

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
ingestor/runMain lila.search.ingestor.App
```

### start ingestor cli tool

```sh
ingestor/runMain lila.search.ingestor.cli --help
```
