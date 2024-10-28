package lila.search
package ingestor

import cats.effect.IO
import cats.effect.kernel.Resource
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.{ Router, Server }
import org.typelevel.otel4s.sdk.exporter.prometheus.*
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter

import scala.concurrent.duration.*

def metricExporterServer(using exporter: MetricExporter.Pull[IO]): Resource[IO, Server] =
  val writerConfig     = PrometheusWriter.Config.default
  val prometheusRoutes = PrometheusHttpRoutes.routes[IO](exporter, writerConfig)
  val routes           = Router("/metrics" -> prometheusRoutes)

  EmberServerBuilder
    .default[IO]
    .withHost(ip"0.0.0.0")
    .withPort(port"9465")
    .withHttpApp(routes.orNotFound)
    .withShutdownTimeout(1.seconds)
    .build
