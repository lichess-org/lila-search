package lila.search
package ingestor

import cats.effect.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.experimental.metrics.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.sdk.exporter.prometheus.autoconfigure.PrometheusMetricExporterAutoConfigure
import org.typelevel.otel4s.sdk.metrics.SdkMetrics

object App extends IOApp.Simple:

  given LoggerFactory[IO] = Slf4jFactory.create[IO]
  given Logger[IO]        = LoggerFactory[IO].getLogger

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      given Meter[IO] <- mkMeter
      _               <- RuntimeMetrics.register[IO]
      _               <- IOMetrics.register[IO]()
      config          <- AppConfig.load.toResource
      _               <- Logger[IO].info(s"Starting lila-search ingestor with config: $config").toResource
      res             <- AppResources.instance(config)
      _               <- IngestorApp(res, config).run()
    yield ()

  def mkMeter = SdkMetrics
    .autoConfigured[IO](_.addExporterConfigurer(PrometheusMetricExporterAutoConfigure[IO]))
    .evalMap(_.meterProvider.get("lila-search-ingestor"))

class IngestorApp(res: AppResources, config: AppConfig)(using Logger[IO], LoggerFactory[IO]):
  def run(): Resource[IO, Unit] =
    given ESClient[IO] = res.elastic
    Ingestors(res.lichess, res.study, res.studyLocal, res.store, config.ingestor)
      .flatMap(_.run())
      .toResource
      .evalTap(_ => Logger[IO].info("Ingestor started"))
