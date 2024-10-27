package lila.search
package app

import cats.effect.*
import org.typelevel.log4cats.slf4j.{ Slf4jFactory, Slf4jLogger }
import org.typelevel.log4cats.{ Logger, LoggerFactory }
import org.typelevel.otel4s.experimental.metrics.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.sdk.*
import org.typelevel.otel4s.sdk.exporter.prometheus.autoconfigure.PrometheusMetricExporterAutoConfigure
import org.typelevel.otel4s.sdk.metrics.SdkMetrics

object App extends IOApp.Simple:

  given Logger[IO]        = Slf4jLogger.getLogger[IO]
  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  override def run: IO[Unit] = app.useForever

  def app: Resource[IO, Unit] =
    for
      config          <- AppConfig.load.toResource
      _               <- Logger[IO].info(s"Starting lila-search with config: $config").toResource
      given Meter[IO] <- mkMeter
      _               <- RuntimeMetrics.register[IO]
      res             <- AppResources.instance(config)
      _               <- SearchApp(res, config).run()
    yield ()

  def mkMeter = SdkMetrics
    .autoConfigured[IO](_.addExporterConfigurer(PrometheusMetricExporterAutoConfigure[IO]))
    .evalMap(_.meterProvider.get("lila-search"))

class SearchApp(res: AppResources, config: AppConfig)(using Logger[IO], LoggerFactory[IO], Meter[IO]):
  def run(): Resource[IO, Unit] =
    for
      httpRoutes <- Routes(res, config.server)
      server     <- MkHttpServer.apply.newEmber(config.server, httpRoutes.orNotFound)
      _ <- Logger[IO].info(s"Starting server on ${config.server.host}:${config.server.port}").toResource
    yield ()
