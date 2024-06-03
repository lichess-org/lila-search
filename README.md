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
sbt test
```

Run code format and auto code refactor with scalafmt & scalafix:
```sh
sbt prepare
```
