package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import chess.Clock.Config
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
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant
import scala.concurrent.duration.*

import Repo.{ *, given }

object GameRepo:

  private val interestedOperations = List(UPDATE, DELETE).map(_.getValue)
  private val eventFilter = Filter.in("operationType", interestedOperations)

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
    val statusFilter = Filter.gte("s", 30)
    val noImportFilter = Filter.ne("so", 7)
    // us fields is the list of player ids, if it's missing then it's
    // an all anonymous (or anonymous vs stockfish) game
    val noAllAnonFilter = Filter.exists("us")
    statusFilter.and(noImportFilter).and(noAllAnonFilter)

  // https://github.com/lichess-org/lila/blob/65e6dd88e99cfa0068bc790a4518a6edb3513f54/modules/gameSearch/src/main/GameSearchApi.scala#L52
  val changeFilter: Filter =
    // Filter games that finished
    // https://github.com/lichess-org/scalachess/blob/18edf46a50445048fdc2ee5a83752e5b3884f490/core/src/main/scala/Status.scala#L18-L27
    val statusFilter = Filter.gte("fullDocument.s", 30)
    val noImportFilter = Filter.ne("fullDocument.so", 7)
    // us fields is the list of player ids, if it's missing then it's
    // an all anonymous (or anonymous vs stockfish) game
    val noAllAnonFilter = Filter.exists("fullDocument.us")
    statusFilter.and(noImportFilter).and(noAllAnonFilter)

  private val aggregate =
    Aggregate.matchBy(eventFilter.and(changeFilter)).combinedWith(Aggregate.project(eventProjection))

  def apply(mongo: MongoDatabase[IO], config: IngestorConfig.Game)(using
      LoggerFactory[IO]
  ): IO[Repo[DbGame]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    mongo.getCollectionWithCodec[DbGame]("game5").map(apply(config))

  def apply(config: IngestorConfig.Game)(games: MongoCollection[IO, DbGame])(using
      Logger[IO]
  ): Repo[DbGame] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[DbGame]] =
      changes(since)
        .map: events =>
          val lastEventTimestamp = events.lastOption.flatMap(_.clusterTime).flatMap(_.asInstant)
          val (toDelete, toIndex) = events.partition(_.operationType == DELETE)
          Result(
            toIndex.flatten(using _.fullDocument.map(g => g.id -> g)),
            toDelete.flatten(using _.docId.map(Id.apply)),
            lastEventTimestamp
          )

    def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[DbGame]] =
      val filter = range(F.createdAt)(since, until.some)
      fs2.Stream.eval(info"Fetching games from $since to $until") *>
        games
          .find(filter.and(gameFilter))
          // .hint("ca_-1")
          .boundedStream(config.batchSize)
          .chunkN(config.batchSize)
          .map(_.toList)
          .metered(1.second) // to avoid overloading the elasticsearch
          .map(ds => Result(ds.map(g => g.id -> g), Nil, none))

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

  object F:
    val createdAt = "ca"
    val updatedAt = "ua"

type PlayerId = String
case class DbGame(
    id: String, // _id
    players: List[PlayerId], // us
    winnerId: Option[PlayerId], // wid
    createdAt: Instant, // ca
    movedAt: Instant, // ua
    ply: Int, // t
    analysed: Option[Boolean], // an
    whitePlayer: Option[DbPlayer], // p0
    blackPlayer: Option[DbPlayer], // p1
    playerIds: String, // is
    binaryPieces: Option[Array[Byte]], // ps
    huffmanPgn: Option[Array[Byte]], // hp
    status: Int, // s
    encodedClock: Option[Array[Byte]], // c
    moveTimes: Option[Array[Byte]], // mt
    encodedWhiteClock: Option[Array[Byte]], // cw
    encodedBlackClock: Option[Array[Byte]], // cb
    rated: Option[Boolean], // ra
    variant: Option[Int], // v
    source: Option[Int], // so
    winnerColor: Option[Boolean] // w
):
  def clockConfig: Option[Config] = encodedClock.flatMap(ClockDecoder.read)
  def clockInit: Option[Int] = clockConfig.map(_.limitSeconds.value)
  def clockInc: Option[Int] = clockConfig.map(_.incrementSeconds.value)
  def whiteId: Option[PlayerId] = players.headOption
  def blackId: Option[PlayerId] = players.lift(1)
  def variantOrDefault: Variant = Variant.idOrDefault(variant.map(Variant.Id.apply))
  def speed: Speed = Speed(clockConfig)
  def loser: Option[PlayerId] = players.find(_.some != winnerId)
  def aiLevel: Option[Int] = whitePlayer.flatMap(_.aiLevel).orElse(blackPlayer.flatMap(_.aiLevel))

  def shouldDebug =
    whitePlayer.isEmpty || blackPlayer.isEmpty

object DbGame:
  // format: off
  given Decoder[DbGame] = Decoder.forProduct21(
    "_id", "us", "wid", "ca", "ua", "t", "an", "p0", "p1", "is", "ps",
    "hp", "s", "c", "mt", "cw", "cb", "ra", "v", "so", "w")(DbGame.apply)
  // format: on

  // We don't write to the database so We don't need to implement this
  given Encoder[DbGame] = new Encoder[DbGame]:
    def apply(a: DbGame): Json = ???

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

  def read(ba: Array[Byte]): Option[Clock.Config] =
    ba.take(2).map(toInt) match
      case Array(b1, b2) => Clock.Config(readClockLimit(b1), Clock.IncrementSeconds(b2)).some
      case _ => None
