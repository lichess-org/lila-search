package lila.search
package app

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.server.Router
import org.typelevel.otel4s.sdk.exporter.prometheus.*
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter

def mkPrometheusRoutes(using exporter: MetricExporter.Pull[IO]): HttpRoutes[IO] =
  val writerConfig = PrometheusWriter.Config.default
  val prometheusRoutes = PrometheusHttpRoutes.routes[IO](exporter, writerConfig)
  Router("/metrics" -> prometheusRoutes)
