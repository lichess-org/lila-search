package lila.search
package ingestor

import cats.effect.*
import cats.effect.std.Mutex
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fs2.io.file.Files

import java.time.Instant

trait KVStore:
  def get(key: String): IO[Option[Instant]]
  def put(key: String, value: Instant): IO[Unit]

object KVStore:

  val file: String                        = "store.json"
  given JsonValueCodec[Map[String, Long]] = JsonCodecMaker.make

  type State = Map[String, Long]
  def apply(): IO[KVStore] =
    Mutex
      .apply[IO]
      .map: mutex =>
        new KVStore:

          def get(key: String): IO[Option[Instant]] =
            read(file)
              .map: content =>
                content.get(key).map(Instant.ofEpochSecond)

          def put(key: String, value: Instant): IO[Unit] =
            mutex.lock.surround:
              read(file).flatMap: content =>
                write(
                  file,
                  content.updated(key, value.getEpochSecond + 1)
                ) // +1 to avoid reindexing the same data

  private def read(path: String): IO[State] =
    Files[IO]
      .readAll(fs2.io.file.Path(path))
      .through(fs2.text.utf8.decode[IO])
      .compile
      .string
      .map(x => readFromString[State](x))
      .handleError(_ => Map.empty)

  private def write(path: String, content: State): IO[Unit] =
    fs2.Stream
      .eval(IO(writeToString(content)))
      .through(fs2.text.utf8.encode[IO])
      .through(Files[IO].writeAll(fs2.io.file.Path(path)))
      .compile
      .drain
