package lila.search
package ingestor

import cats.effect.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.experimental.metrics.*
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.{ Meter, MeterProvider }
import org.typelevel.otel4s.sdk.exporter.prometheus.autoconfigure.PrometheusMetricExporterAutoConfigure
import org.typelevel.otel4s.sdk.metrics.SdkMetrics

object App extends IOApp.Simple:

  given LoggerFactory[IO] = Slf4jFactory.create[IO]
  given Logger[IO] = LoggerFactory[IO].getLogger

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      otel4s <- SdkMetrics.autoConfigured[IO](configBuilder)
      given MeterProvider[IO] = otel4s.meterProvider
      _ <- registerRuntimeMetrics
      config <- AppConfig.load.toResource
      _ <- Logger[IO].info(s"Starting lila-search ingestor with config: ${config.toString}").toResource
      _ <- Logger[IO].info(s"BuildInfo: ${BuildInfo.toString}").toResource
      res <- AppResources.instance(config)
      _ <- IngestorApp(res, config).run()
    yield ()

  private def registerRuntimeMetrics(using MeterProvider[IO]): Resource[IO, Unit] =
    for
      _ <- IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default)
      given Meter[IO] <- MeterProvider[IO].get("jvm.runtime").toResource
      _ <- RuntimeMetrics.register[IO]
    yield ()

  private def configBuilder(builder: SdkMetrics.AutoConfigured.Builder[IO]) =
    builder
      .addPropertiesCustomizer(_ =>
        Map(
          "otel.metrics.exporter" -> "none",
          "otel.traces.exporter" -> "none"
        )
      )
      .addExporterConfigurer(PrometheusMetricExporterAutoConfigure[IO])

class IngestorApp(res: AppResources, config: AppConfig)(using Logger[IO], LoggerFactory[IO]):
  def run(): Resource[IO, Unit] =
    Ingestors(res.lichess, res.study, res.studyLocal, res.store, res.elastic, config.ingestor)
      .flatMap(_.run())
      .toResource
      .evalTap(_ => Logger[IO].info("Ingestor started"))
