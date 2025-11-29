package lila.search
package ingestor

import cats.syntax.all.*
import cats.effect.IO
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * Generates a infinite stream of (since, until) Instant pairs,
 * starting from current time or `startAt` (if defined)
 * Since and until are separated by `interval`
 * also metered to emit a new pair every `interval`
 * for example with startAt = None and interval = 1.hour
 * it will produce:
 *   (now, now + 1.hour)
 *   (now + 1.hour, now + 2.hour)
 *   (now + 2.hour, now + 3.hour)
 *   ...
 */
def intervalStream(startAt: Option[Instant], interval: FiniteDuration): fs2.Stream[IO, (Instant, Instant)] =
  (startAt.fold(fs2.Stream.empty)(since => fs2.Stream(since))
    ++ fs2.Stream
      .eval(IO.realTimeInstant)
      .flatMap(now => fs2.Stream.unfold(now)(s => (s, s.plusSeconds(interval.toSeconds)).some))).zipWithNext
    .map((since, until) => since -> until.get)
    .meteredStartImmediately(interval)
