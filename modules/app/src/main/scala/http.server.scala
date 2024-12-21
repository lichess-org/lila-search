package lila.search
package app

import cats.effect.{ IO, Resource }
import fs2.io.net.Network
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

trait MkHttpServer:
  def newEmber(cfg: HttpServerConfig, httpApp: HttpApp[IO]): Resource[IO, Server]

object MkHttpServer:

  def apply()(using server: MkHttpServer): MkHttpServer = server

  given Logger[IO] => MkHttpServer = new:

    def newEmber(cfg: HttpServerConfig, httpApp: HttpApp[IO]): Resource[IO, Server] = EmberServerBuilder
      .default[IO]
      .withHost(cfg.host)
      .withPort(cfg.port)
      .withHttpApp(httpApp)
      .withShutdownTimeout(cfg.shutdownTimeout.seconds)
      .build
      .evalTap(showBanner)

    private def showBanner(s: Server): IO[Unit] =
      Logger[IO].info(s"lila-search started at ${s.address}")
