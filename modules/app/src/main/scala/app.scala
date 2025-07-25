package lila.search
package app

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.experimental.metrics.*
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.{ Meter, MeterProvider }
import org.typelevel.otel4s.sdk.exporter.prometheus.PrometheusMetricExporter
import org.typelevel.otel4s.sdk.metrics.SdkMetrics
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter

object App extends IOApp.Simple:

  given LoggerFactory[IO] = Slf4jFactory.create[IO]
  given Logger[IO] = LoggerFactory[IO].getLogger

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      given MetricExporter.Pull[IO] <- PrometheusMetricExporter.builder[IO].build.toResource
      given Meter[IO] <- mkMeter
      _ <- RuntimeMetrics.register[IO]
      config <- AppConfig.load.toResource
      _ <- Logger[IO].info(s"Starting lila-search with config: ${config.toString}").toResource
      res <- AppResources.instance(config)
      _ <- mkServer(res, config)
    yield ()

  def mkMeter(using exporter: MetricExporter.Pull[IO]) = SdkMetrics
    .autoConfigured[IO](_.addMeterProviderCustomizer((b, _) => b.registerMetricReader(exporter.metricReader)))
    .flatMap: sdk =>
      given meterProvider: MeterProvider[IO] = sdk.meterProvider
      IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default) *>
        meterProvider.get("lila-search").toResource

  def mkServer(res: AppResources, config: AppConfig)(using
      Meter[IO],
      MetricExporter.Pull[IO]
  ): Resource[IO, Unit] =
    for
      apiRoutes <- Routes(res, config.server)
      httpRoutes = apiRoutes <+> mkPrometheusRoutes
      _ <- MkHttpServer().newEmber(config.server, httpRoutes.orNotFound)
      _ <- Logger[IO].info(s"BuildInfo: ${BuildInfo.toString}").toResource
    yield ()
