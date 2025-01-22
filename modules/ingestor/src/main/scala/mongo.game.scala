package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import chess.Speed
import chess.variant.*
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType.*
import io.circe.*
import mongo4cats.circe.*
import mongo4cats.collection.MongoCollection
import mongo4cats.database.MongoDatabase
import mongo4cats.models.collection.ChangeStreamDocument
import mongo4cats.operations.{ Aggregate, Filter, Projection }
import org.bson.BsonTimestamp
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant
import scala.concurrent.duration.*

import Repo.{ *, given }

object GameRepo:

  private val interestedOperations = List(UPDATE, DELETE).map(_.getValue)
  private val eventFilter          = Filter.in("operationType", interestedOperations)

  private val interestedEventFields =
    List(
      "operationType",
      "clusterTime",
      "documentKey._id",
      "fullDocument"
    ) // TODO only include interestedFields

  private val eventProjection = Projection.include(interestedEventFields)

  // https://github.com/lichess-org/lila/blob/65e6dd88e99cfa0068bc790a4518a6edb3513f54/modules/gameSearch/src/main/GameSearchApi.scala#L52
  val gameFilter: Filter =
    // Filter games that finished
    // https://github.com/lichess-org/scalachess/blob/18edf46a50445048fdc2ee5a83752e5b3884f490/core/src/main/scala/Status.scala#L18-L27
    val statusFilter   = Filter.gte("s", 30)
    val noImportFilter = Filter.ne("so", 7)
    // us fields is the list of player ids, if it's missing then it's
    // an all anonymous (or anonymous vs stockfish) game
    val noAllAnonFilter = Filter.exists("us")
    statusFilter.and(noImportFilter).and(noAllAnonFilter)

  // https://github.com/lichess-org/lila/blob/65e6dd88e99cfa0068bc790a4518a6edb3513f54/modules/gameSearch/src/main/GameSearchApi.scala#L52
  val changeFilter: Filter =
    // Filter games that finished
    // https://github.com/lichess-org/scalachess/blob/18edf46a50445048fdc2ee5a83752e5b3884f490/core/src/main/scala/Status.scala#L18-L27
    val statusFilter   = Filter.gte("fullDocument.s", 30)
    val noImportFilter = Filter.ne("fullDocument.so", 7)
    // us fields is the list of player ids, if it's missing then it's
    // an all anonymous (or anonymous vs stockfish) game
    val noAllAnonFilter = Filter.exists("fullDocument.us")
    statusFilter.and(noImportFilter).and(noAllAnonFilter)

  private val aggregate =
    Aggregate.matchBy(eventFilter.and(changeFilter)).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], config: IngestorConfig.Game)(using
      LoggerFactory[IO]
  ): IO[Repo[GameSource]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    mongo.getCollectionWithCodec[DbGame]("game5").map(apply(config))

  def apply(config: IngestorConfig.Game)(games: MongoCollection[IO, DbGame])(using
      Logger[IO]
  ): Repo[GameSource] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[GameSource]] =
      changes(since)
        .map: events =>
          val lastEventTimestamp  = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
          val (toDelete, toIndex) = events.partition(_.operationType == DELETE)
          Result(
            toIndex.flatten(_.fullDocument.map(_.toSource)),
            toDelete.flatten(_.docId.map(Id.apply)),
            lastEventTimestamp
          )

    def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[GameSource]] =
      val filter = range(F.createdAt)(since, until.some)
        .or(range(F.updatedAt)(since, until.some))
      fs2.Stream.eval(info"Fetching games from $since to $until") *>
        games
          .find(filter.and(gameFilter))
          .hint("ca_-1")
          // .projection(postProjection)
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList)
          .metered(1.second) // to avoid overloading the elasticsearch
          .map(ds => Result(ds.map(_.toSource), Nil, none))

    private def changes(since: Option[Instant]): fs2.Stream[IO, List[ChangeStreamDocument[DbGame]]] =
      val builder = games.watch(aggregate)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .fullDocument(FullDocument.UPDATE_LOOKUP) // this is required for update event
        .batchSize(config.batchSize)
        .boundedStream(config.batchSize)
        .groupWithin(config.batchSize, config.timeWindows.second)
        .evalTap(
          _.traverse_(x =>
            info"Received $x without p0 or p1 fields".whenA(x.fullDocument.exists(_.shouldDebug))
          )
        )
        .map(_.toList.distincByDocId)
        .evalTap(_.traverse_(x => x.fullDocument.traverse_(x => debug"${x.debug}")))

  object F:
    val createdAt = "ca"
    val updatedAt = "ua"

type PlayerId = String
case class DbGame(
    id: String,                             // _id
    players: List[PlayerId],                // us
    winnerId: Option[PlayerId],             // wid
    createdAt: Instant,                     // ca
    movedAt: Instant,                       // ua
    ply: Int,                               // t
    analysed: Option[Boolean],              // an
    whitePlayer: Option[DbPlayer],          // p0
    blackPlayer: Option[DbPlayer],          // p1
    playerIds: String,                      // is
    binaryPieces: Option[Array[Byte]],      // ps
    huffmanPgn: Option[Array[Byte]],        // hp
    status: Int,                            // s
    encodedClock: Option[Array[Byte]],      // c
    moveTimes: Option[Array[Byte]],         // mt
    encodedWhiteClock: Option[Array[Byte]], // cw
    encodedBlackClock: Option[Array[Byte]], // cb
    rated: Option[Boolean],                 // ra
    variant: Option[Int],                   // v
    source: Option[Int],                    // so
    winnerColor: Option[Boolean]            // w
):
  def clockConfig      = encodedClock.flatMap(ClockDecoder.read).map(_.white)
  def clockInit        = clockConfig.map(_.limitSeconds.value)
  def clockInc         = clockConfig.map(_.incrementSeconds.value)
  def whiteId          = players.headOption
  def blackId          = players.lift(1)
  def variantOrDefault = Variant.idOrDefault(variant.map(Variant.Id.apply))
  def speed            = Speed(clockConfig)
  def loser            = players.find(_.some != winnerId)
  def aiLevel          = whitePlayer.flatMap(_.aiLevel).orElse(blackPlayer.flatMap(_.aiLevel))

  // https://github.com/lichess-org/lila/blob/65e6dd88e99cfa0068bc790a4518a6edb3513f54/modules/core/src/main/game/Game.scala#L261
  private def averageUsersRating =
    List(whitePlayer.flatMap(_.rating), blackPlayer.flatMap(_.rating)).flatten match
      case a :: b :: Nil => Some((a + b) / 2)
      case a :: Nil      => Some((a + 1500) / 2)
      case _             => None

  // https://github.com/lichess-org/lila/blob/02ac57c4584b89a0df8f343f34074c0135c2d2b4/modules/core/src/main/game/Game.scala#L90-L97
  def durationSeconds: Option[Int] =
    val seconds = (movedAt.toEpochMilli / 1000 - createdAt.toEpochMilli / 1000)
    Option.when(seconds < 60 * 60 * 12)(seconds.toInt)

  def toSource =
    id ->
      GameSource(
        status = status,
        turns = (ply + 1) / 2,
        rated = rated.getOrElse(false),
        perf = DbGame.perfId(variantOrDefault, speed),
        winnerColor = winnerColor.fold(3)(if _ then 1 else 2),
        date = SearchDateTime.fromInstant(movedAt),
        analysed = analysed.getOrElse(false),
        uids = players.some, // make usid not optional
        winner = winnerId,
        loser = loser,
        averageRating = averageUsersRating,
        ai = aiLevel,
        duration = durationSeconds,
        clockInit = clockInit,
        clockInc = clockInc,
        whiteUser = whiteId,
        blackUser = blackId,
        source = source
      )

  def shouldDebug =
    whitePlayer.isEmpty || blackPlayer.isEmpty

  def debug =
    import smithy4s.json.Json.given
    import com.github.plokhotnyuk.jsoniter_scala.core.*
    id -> writeToString(toSource._2)

object DbGame:
  // format: off
  given Decoder[DbGame] = Decoder.forProduct21(
    "_id", "us", "wid", "ca", "ua", "t", "an", "p0", "p1", "is", "ps",
    "hp", "s", "c", "mt", "cw", "cb", "ra", "v", "so", "w")(DbGame.apply)
  // format: on

  // We don't write to the database so We don't need to implement this
  given Encoder[DbGame] = new Encoder[DbGame]:
    def apply(a: DbGame): Json = ???

  def perfId(variant: Variant, speed: Speed): Int =
    variant.match
      case Standard | FromPosition =>
        speed match
          case Speed.UltraBullet    => 0
          case Speed.Bullet         => 1
          case Speed.Blitz          => 2
          case Speed.Rapid          => 6
          case Speed.Classical      => 3
          case Speed.Correspondence => 4
      case Crazyhouse    => 18
      case Chess960      => 11
      case KingOfTheHill => 12
      case ThreeCheck    => 15
      case Antichess     => 13
      case Atomic        => 14
      case Horde         => 16
      case RacingKings   => 17

case class DbPlayer(
    rating: Option[Int],
    ratingDiff: Option[Int],
    berserk: Option[Boolean],
    aiLevel: Option[Int],
    provisional: Option[Boolean],
    name: Option[String]
)

object DbPlayer:
  given Decoder[DbPlayer] = Decoder.forProduct6("e", "d", "be", "ai", "p", "na")(DbPlayer.apply)
  given Encoder[DbPlayer] = Encoder.forProduct6("e", "d", "be", "ai", "p", "na")(p =>
    (p.rating, p.ratingDiff, p.berserk, p.aiLevel, p.provisional, p.name)
  )

object ClockDecoder:
  import chess.*
  private def readClockLimit(i: Int) = Clock.LimitSeconds(if i < 181 then i * 60 else (i - 180) * 15)

  private inline def toInt(inline b: Byte): Int = b & 0xff

  def read(ba: Array[Byte]): Option[ByColor[Clock.Config]] =
    ByColor: color =>
      ba.take(2).map(toInt) match
        case Array(b1, b2) => Clock.Config(readClockLimit(b1), Clock.IncrementSeconds(b2)).some
        case _             => None
