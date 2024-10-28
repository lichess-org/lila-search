package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.{ Slf4jFactory, Slf4jLogger }
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.experimental.metrics.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.sdk.exporter.prometheus.PrometheusMetricExporter
import org.typelevel.otel4s.sdk.metrics.SdkMetrics
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter

object App extends IOApp.Simple:

  given Logger[IO]        = Slf4jLogger.getLogger[IO]
  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      given MetricExporter.Pull[IO] <- PrometheusMetricExporter.builder[IO].build.toResource
      given Meter[IO]               <- mkMeter
      _                             <- RuntimeMetrics.register[IO]
      config                        <- AppConfig.load.toResource
      _   <- Logger[IO].info(s"Starting lila-search ingestor with config: $config").toResource
      res <- AppResources.instance(config)
      _   <- IngestorApp(res, config).run()
    yield ()

  def mkMeter(using exporter: MetricExporter.Pull[IO]) = SdkMetrics
    .autoConfigured[IO](_.addMeterProviderCustomizer((b, _) => b.registerMetricReader(exporter.metricReader)))
    .evalMap(_.meterProvider.get("lila-search-ingestor"))

class IngestorApp(res: AppResources, config: AppConfig)(using
    Logger[IO],
    LoggerFactory[IO],
    MetricExporter.Pull[IO]
):
  def run(): Resource[IO, Unit] =
    val ingestor: ResourceIO[Unit] =
      Ingestor(res.lichess, res.study, res.studyLocal, res.elastic, res.store, config.ingestor)
        .flatMap(_.run())
        .toResource
    (ingestor, metricExporterServer).parTupled.void
      .evalTap(_ => Logger[IO].info("Ingestor started"))
