package lila.search
package ingestor

import cats.effect.{ IO, Ref, Resource }
import cats.syntax.all.*
import io.circe.{ Decoder, Encoder }
import mongo4cats.circe.*
import mongo4cats.database.MongoDatabase
import mongo4cats.operations.{ Filter, Projection }
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import scala.concurrent.duration.*

trait BotUserCache:
  def get: IO[Set[String]]

object BotUserCache:

  def apply(lichess: MongoDatabase[IO], refreshInterval: FiniteDuration = 5.minutes)(using
      LoggerFactory[IO]
  ): Resource[IO, BotUserCache] =
    val logger = LoggerFactory[IO].getLoggerFromName("bot.cache")
    for
      initial <- Resource.eval(fetchBotIds(lichess, logger))
      _ <- Resource.eval(
        IO.raiseError(new IllegalStateException("BotUserCache: loaded 0 bot IDs from user4 collection"))
          .whenA(initial.isEmpty)
      )
      ref <- Resource.eval(Ref.of[IO, Set[String]](initial))
      _ <- refreshLoop(lichess, ref, refreshInterval, logger).background
    yield new BotUserCache:
      def get: IO[Set[String]] = ref.get

  private def fetchBotIds(lichess: MongoDatabase[IO], logger: Logger[IO]): IO[Set[String]] =
    lichess
      .getCollectionWithCodec[BotUser]("user4")
      .flatMap:
        _.find(Filter.eq("title", "BOT"))
          .projection(Projection.include("_id"))
          .stream
          .map(_.id)
          .compile
          .to(Set)
      .flatTap(ids => logger.info(s"Loaded ${ids.size} bot user IDs"))

  private def refreshLoop(
      lichess: MongoDatabase[IO],
      ref: Ref[IO, Set[String]],
      interval: FiniteDuration,
      logger: Logger[IO]
  ): IO[Nothing] =
    (IO.sleep(interval) *> fetchBotIds(lichess, logger).flatMap(ref.set)).foreverM

  private case class BotUser(id: String)
  private object BotUser:
    given Decoder[BotUser] = Decoder.forProduct1("_id")(BotUser.apply)
    given Encoder[BotUser] = Encoder.forProduct1("_id")(_.id)
