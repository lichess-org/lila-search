package lila.search
package ingestor

import cats.effect.*
import cats.mtl.Local
import cats.syntax.all.*
import io.opentelemetry.api.{ GlobalOpenTelemetry, OpenTelemetry as JOpenTelemetry }
import io.opentelemetry.instrumentation.runtimemetrics.java17.*
import org.typelevel.log4cats.slf4j.{ Slf4jFactory, Slf4jLogger }
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.instances.local.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.Context

object App extends IOApp.Simple:

  given Logger[IO]        = Slf4jLogger.getLogger[IO]
  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  given (using Local[IO, Context]): IO[OtelJava[IO]] =
    IO(GlobalOpenTelemetry.get).map(OtelJava.local[IO])

  given [F[_]: Async](using otel4s: OtelJava[F]): F[Meter[F]] =
    otel4s.meterProvider.get("lila-search")

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      given IOLocal[Context] <- IOLocal(Context.root).toResource
      given OtelJava[IO]     <- summon[IO[OtelJava[IO]]].toResource
      given Meter[IO]        <- summon[IO[Meter[IO]]].toResource
      _                      <- registerRuntimeMetrics[IO](summon[OtelJava[IO]].underlying)
      config                 <- AppConfig.load.toResource
      _   <- Logger[IO].info(s"Starting lila-search ingestor with config: $config").toResource
      res <- AppResources.instance(config)
      _   <- IngestorApp(res, config).run()
    yield ()

  private def registerRuntimeMetrics[F[_]: Sync](openTelemetry: JOpenTelemetry): Resource[F, Unit] =
    val acquire = Sync[F].delay(RuntimeMetrics.create(openTelemetry))
    Resource.make(acquire)(r => Sync[F].delay(r.close())).void

class IngestorApp(res: AppResources, config: AppConfig)(using Logger[IO], LoggerFactory[IO]):
  def run(): Resource[IO, Unit] =
    Ingestor(res.lichess, res.study, res.studyLocal, res.elastic, res.store, config.ingestor)
      .flatMap(_.run())
      .toResource
      .evalTap(_ => Logger[IO].info("Ingestor started"))
