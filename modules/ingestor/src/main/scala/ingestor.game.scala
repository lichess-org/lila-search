package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import com.mongodb.client.model.changestream.FullDocument
import com.monovore.decline.*
import io.circe.*
import mongo4cats.circe.*
import mongo4cats.collection.MongoCollection
import mongo4cats.operations.{ Aggregate, Filter }
import org.bson.BsonTimestamp
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

import java.time.Instant
import scala.concurrent.duration.*

trait GameIngestor:
  // watch change events from game5 collection
  def watch(since: Instant, until: Instant): IO[Unit]

object GameIngestor:

  def apply(games: MongoCollection[IO, DbGame])(using Logger[IO]): GameIngestor = new:

    def watch(since: Instant, until: Instant): IO[Unit] =
      changes(since, until).compile.drain

    private def changes(
        since: Instant,
        until: Instant
    ): fs2.Stream[IO, List[DbGame]] =
      val batchSize  = 1000
      val timeWindow = 10
      games
        .watch(aggreate(since, until))
        .startAtOperationTime(BsonTimestamp(since.getEpochSecond.toInt, 1))
        .batchSize(batchSize)                     // config.batchSize
        .fullDocument(FullDocument.UPDATE_LOOKUP) // this is required for update event
        .boundedStream(batchSize)
        .groupWithin(batchSize, timeWindow.second) // config.windows
        .evalTap(_.traverse_(x => info"received $x"))
        .evalTap(_.filter(_.fullDocument.exists(_.validClock)).traverse_(x => info"count $x"))
        .map(_.toList.map(_.fullDocument).flatten)
        .evalTap(_.traverse_(x => info"clock ${x.clock}"))
        .map(_.filter(_.validClock))
        .evalTap(_.traverse_(x => info"valid clock ${x.clock}"))

    private def aggreate(since: Instant, until: Instant) =
      // games have at least 15 moves
      val turnsFilter    = Filter.gte("fullDocument.t", 30)
      val standardFilter = Filter.eq("fullDocument.v", 1).or(Filter.notExists("fullDocument.v"))
      val ratedFilter    = Filter.eq("fullDocument.ra", true)
      val noAiFilter =
        Filter
          .eq("fullDocument.p0.ai", 0)
          .or(Filter.notExists("fullDocument.p0.ai"))
          .and(Filter.eq("fullDocument.p1.ai", 0).or(Filter.notExists("fullDocument.p1.ai")))

      // Filter games that finished with Mate, Resign, Stalemate, Draw, Outoftime, Timeout
      // https://github.com/lichess-org/scalachess/blob/master/core/src/main/scala/Status.scala#L18-L23
      val statusFilter = Filter.in("fullDocument.s", List(30, 31, 32, 33, 34, 35))

      // filter games that played and ended between since and until
      val playedTimeFilter =
        Filter
          .gte("fullDocument.ca", since)
          .and(Filter.lte("fullDocument.ua", until))

      val updatedStatusOnlyFilter = Filter
        .exists("updateDescription.updatedFields.s")
        .or(Filter.notExists("updateDescription"))

      // required clock config
      val clockFilter = Filter.exists("fullDocument.c")

      // only human plays
      val noImportGameFilter = Filter.notExists("fullDocument.pgni")

      val gameFilter = standardFilter
        .and(turnsFilter)
        .and(ratedFilter)
        .and(noAiFilter)
        .and(statusFilter)
        .and(updatedStatusOnlyFilter)
        .and(playedTimeFilter)
        .and(clockFilter)
        .and(noImportGameFilter)

      Aggregate.matchBy(gameFilter)

      /*
       *{
  _id: 'TaHSAsYD',
  us: [ 'pedro', 'ikem' ],
  is: 'AABgAACA',
  p0: { e: 2264, d: 3 },
  p1: { e: 2069, d: -3 },
  s: 35,
  t: 97,
  v: 1,
  ra: true,
  ca: ISODate('2024-02-29T17:06:28.099Z'),
  ua: ISODate('2024-02-29T18:06:00.000Z'),
  so: 5,
  hp: Binary.createFromBase64('PDVvHX7zZXUV51aObVt//n+PYCv+TKzL6zXvcTbiqlrv551I5GeN/S1rL74i/K9a68ahbTaRzHvo9uTN2+dc', 0),
  an: false,
  c: Binary.createFromBase64('CgAA38IA6mQ=', 0),
  cw: Binary.createFromBase64('wGAhen8MW6C28eG7gbi0S7rEzAvMPLTu1KHlhKUh18BT3zxTY9SgQYDTvHlPCf6QIyB8SJ/PQYihGoTkZtLA', 0),
  cb: Binary.createFromBase64('vHARnEBZLL6hkblax4GSWiwXenwwssK8dk8LyS4sfqG7D1eo43kerh70+MHF8Bm0ah3leztkZ5KI4yrsrCrO8piEWFA=', 0),
  w: true,
  wid: 'pedro'
}
       * */

case class DbGame(
    id: String,                     // _id
    players: List[String],  // us
    whitePlayer: DbPlayer,          // p0
    blackPlayer: DbPlayer,          // p1
    status: Int,                    // s
    huffmanPgn: Array[Byte],        // hp
    encodedClock: Array[Byte],      // c
    encodedWhiteClock: Array[Byte], // cw
    encodedBlackClock: Array[Byte], // cb
    turn: Int,                      // t
    createdAt: Instant,             // ca
    moveAt: Instant,                 // ua
    rated: Boolean,  // ra
):
  def clock               = ClockDecoder.read(encodedClock)
  def validClock: Boolean = clock.exists(_.forall(_.sastify))

val minTotalSeconds = 5 * 60      // 5 minutes
val maxTotalSeconds = 8 * 60 * 60 // 8 hours

object DbGame:

  given Decoder[DbGame] =
    Decoder.forProduct12("_id", "us", "p0", "p1", "s", "hp", "c", "cw", "cb", "t", "ca", "ua")(DbGame.apply)

  given Encoder[DbGame] =
    Encoder.forProduct12("_id", "us", "p0", "p1", "s", "hp", "c", "cw", "cb", "t", "ca", "ua")(g =>
      (
        g.id,
        g.players,
        g.whitePlayer,
        g.blackPlayer,
        g.status,
        g.huffmanPgn,
        g.encodedClock,
        g.encodedWhiteClock,
        g.encodedBlackClock,
        g.turn,
        g.createdAt,
        g.moveAt
      )
    )

case class DbPlayer(rating: Option[Int], ratingDiff: Option[Int], berserk: Option[Boolean]):
  def isBerserked = berserk.contains(true)

extension (config: chess.Clock.Config)

  // over 60 moves
  def estimateTotalSecondsOver60Moves = config.limitSeconds.value + 60 * config.incrementSeconds.value

  // Games are equal to or longer than 3+2 / 5+0 or equivalent over 60 moves (e.g., 4+1, 0+30, etc),
  // but not more than 8h (e.g., no 240+60)
  def sastify: Boolean =
    minTotalSeconds <= config.estimateTotalSecondsOver60Moves &&
      config.estimateTotalSecondsOver60Moves <= maxTotalSeconds

object DbPlayer:
  given Decoder[DbPlayer] = Decoder.forProduct3("e", "d", "be")(DbPlayer.apply)
  given Encoder[DbPlayer] = Encoder.forProduct3("e", "d", "be")(p => (p.rating, p.ratingDiff, p.berserk))

object ClockDecoder:
  import chess.*
  private def readClockLimit(i: Int) = Clock.LimitSeconds(if i < 181 then i * 60 else (i - 180) * 15)

  private inline def toInt(inline b: Byte): Int = b & 0xff

  def read(ba: Array[Byte]): Option[ByColor[Clock.Config]] =
    ByColor: color =>
      ba.take(2).map(toInt) match
        case Array(b1, b2) => Clock.Config(readClockLimit(b1), Clock.IncrementSeconds(b2)).some
        case _             => None
