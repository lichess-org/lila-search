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
      otel4s <- SdkMetrics.autoConfigured[IO]:
        _.addMeterProviderCustomizer((b, _) =>
          b.registerMetricReader(summon[MetricExporter.Pull[IO]].metricReader)
        )
      given MeterProvider[IO] = otel4s.meterProvider
      _ <- IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default)
      given Meter[IO] <- MeterProvider[IO].get("lila-search").toResource
      _ <- RuntimeMetrics.register[IO]
      config <- AppConfig.load.toResource
      _ <- Logger[IO].info(s"Starting lila-search with config: ${config.toString}").toResource
      res <- AppResources.instance(config)
      _ <- mkServer(res, config)
    yield ()

  def mkServer(res: AppResources, config: AppConfig)(using
      MetricExporter.Pull[IO],
      MeterProvider[IO]
  ): Resource[IO, Unit] =
    for
      apiRoutes <- Routes(res, config.server)
      httpRoutes = apiRoutes <+> MkPrometheusRoutes
      _ <- MkHttpServer.newEmber(config.server, httpRoutes.orNotFound)
      _ <- Logger[IO].info(s"BuildInfo: ${BuildInfo.toString}").toResource
    yield ()
