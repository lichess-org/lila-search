package lila.search
package app

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.experimental.metrics.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.sdk.*
import org.typelevel.otel4s.sdk.exporter.prometheus.PrometheusMetricExporter
import org.typelevel.otel4s.sdk.metrics.SdkMetrics
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter

object App extends IOApp.Simple:

  given LoggerFactory[IO] = Slf4jFactory.create[IO]
  given Logger[IO]        = LoggerFactory[IO].getLogger

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      given MetricExporter.Pull[IO] <- PrometheusMetricExporter.builder[IO].build.toResource
      given Meter[IO]               <- mkMeter
      config                        <- AppConfig.load.toResource
      _   <- Logger[IO].info(s"Starting lila-search with config: $config").toResource
      _   <- RuntimeMetrics.register[IO]
      _   <- IOMetrics.register[IO]()
      res <- AppResources.instance(config)
      _   <- mkServer(res, config)
    yield ()

  def mkMeter(using exporter: MetricExporter.Pull[IO]) = SdkMetrics
    .autoConfigured[IO](_.addMeterProviderCustomizer((b, _) => b.registerMetricReader(exporter.metricReader)))
    .evalMap(_.meterProvider.get("lila-search"))

  def mkServer(res: AppResources, config: AppConfig)(using MetricExporter.Pull[IO]): Resource[IO, Unit] =
    for
      apiRoutes <- Routes(res, config.server)
      httpRoutes = apiRoutes <+> mkPrometheusRoutes
      server <- MkHttpServer().newEmber(config.server, httpRoutes.orNotFound)
      _      <- Logger[IO].info(s"Starting server on ${config.server.host}:${config.server.port}").toResource
    yield ()
