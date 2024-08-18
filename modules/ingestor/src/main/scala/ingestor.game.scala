package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType.*
import io.circe.*
import mongo4cats.circe.*
import mongo4cats.collection.MongoCollection
import mongo4cats.operations.{ Aggregate, Filter }
import org.bson.BsonTimestamp
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*

import java.time.Instant
import scala.concurrent.duration.*
import mongo4cats.operations.Projection
import mongo4cats.database.MongoDatabase

trait GameIngestor:
  // watch change events from game5 collection and ingest games into elastic search
  def watch: fs2.Stream[IO, Unit]

object GameIngestor:

  private val index = Index.Game

  private val interestedOperations = List(INSERT, REPLACE).map(_.getValue) // We don't delete games

  // private val interestedFields = List(_id, F.text, F.topicId, F.troll, F.createdAt, F.userId, F.erasedAt)
  // private val postProjection   = Projection.include(interestedFields)

  private val interestedEventFields =
    List(
      "operationType",
      "clusterTime",
      "documentKey._id",
      "fullDocument"
    ) // TODO only include interestedFields
  private val eventProjection = Projection.include(interestedEventFields)

  private val aggregate =
    Aggregate.project(eventProjection)

  def apply(mongo: MongoDatabase[IO], elastic: ESClient[IO], store: KVStore, config: IngestorConfig.Game)(
      using Logger[IO]
  ): IO[GameIngestor] =
    (mongo.getCollectionWithCodec[DbGame]("game5")).map(apply(elastic, store, config))

  def apply(
      elastic: ESClient[IO],
      store: KVStore,
      config: IngestorConfig.Game
  )(games: MongoCollection[IO, DbGame])(using Logger[IO]): GameIngestor = new:

    def watch: fs2.Stream[IO, Unit] =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting forum ingestor from $since"))
        .flatMap: since =>
          changes(since)
            .evalMap(IO.println)

    private def changes(since: Option[Instant]): fs2.Stream[IO, List[DbGame]] =
      val builder = games.watch(aggregate)
      // skip the first event if we're starting from a specific timestamp
      // since the event at that timestamp is already indexed
      val skip = since.fold(0)(_ => 1)
      since
        .fold(builder)(x => builder.startAtOperationTime(x.asBsonTimestamp))
        .fullDocument(FullDocument.UPDATE_LOOKUP) // this is required for update event
        .batchSize(config.batchSize)              // config.batchSize
        .boundedStream(config.batchSize)
        .drop(skip)
        .groupWithin(config.batchSize, config.timeWindows.second) // config.windows
        .evalTap(_.traverse_(x => info"received $x"))
        .map(_.toList.map(_.fullDocument).flatten)
        .evalTap(_.traverse_(x => info"clock ${x.clock}"))
        .map(_.filter(_.validClock))
        .evalTap(_.traverse_(x => info"valid clock ${x.clock}"))

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(Instant.ofEpochSecond(_).some.pure[IO])

final case class GameSource(
    status: Int,
    turns: Int,
    rated: Boolean,
    perf: Int,
    winnerColor: Int,
    date: SearchDateTime,
    analysed: Boolean,
    uids: Option[List[String]] = None,
    winner: Option[String] = None,
    loser: Option[String] = None,
    averageRating: Option[Int] = None,
    ai: Option[Int] = None,
    duration: Option[Int] = None,
    clockInit: Option[Int] = None,
    clockInc: Option[Int] = None,
    whiteUser: Option[String] = None,
    blackUser: Option[String] = None,
    source: Option[Int] = None
)

type PlayerId = String

case class DbGame(
    id: String,                     // _id
    players: List[PlayerId],        // us
    winnerId: Option[PlayerId],     // wid
    createdAt: Instant,             // ca
    moveAt: Instant,                // ua
    turn: Int,                      // t
    analysed: Boolean,              // an
    playingUids: List[PlayerId],    // pl ??? wtf is this
    whitePlayer: DbPlayer,          // p0
    blackPlayer: DbPlayer,          // p1
    playerIds: List[String],        // is
    binaryPieces: Array[Byte],      // ps // ByteVector from scodec
    huffmanPgn: Array[Byte],        // hp
    status: Int,                    // s
    encodedClock: Array[Byte],      // c
    moveTimes: Array[Byte],         // mt
    encodedWhiteClock: Array[Byte], // cw
    encodedBlackClock: Array[Byte], // cb
    rated: Boolean,                 // ra
    variant: Int,                   // v
    source: Int,                    // so
    winnerColor: Option[Boolean]    // w
):
  def clock               = ClockDecoder.read(encodedClock)
  def validClock: Boolean = clock.exists(_.forall(_.sastify))

  def averageUsersRating: Option[Int] = ???

  def toSource: GameSource =
    GameSource(
      status = status,
      turns = (turn + 1) / 2,
      rated = rated,
      perf = variant,
      winnerColor = winnerColor.fold(3)(if _ then 1 else 2),
      date = SearchDateTime.fromInstant(moveAt),
      analysed = analysed,
      uids = playingUids.some.filterNot(_.isEmpty),
      winner = winnerId,
      loser = playerIds.find(_.some != winnerId),
      averageRating = averageUsersRating,
      ai = ???,
      duration = ???,
      clockInit = ???,
      clockInc = ???,
      whiteUser = ???,
      blackUser = ???,
      source = source.some
    )

val minTotalSeconds = 5 * 60      // 5 minutes
val maxTotalSeconds = 8 * 60 * 60 // 8 hours

// object BSONFields:
//
// object BSONFields:
//
//   val id          = "_id"
//   val playerUids  = "us"
//   val winnerId    = "wid"
//   val createdAt   = "ca"
//   val movedAt     = "ua" // ua = updatedAt (bc)
//   val turns       = "t"
//   val analysed    = "an"
//   val pgnImport   = "pgni"
//   val playingUids = "pl"
//   val whitePlayer       = "p0"
//   val blackPlayer       = "p1"
//   val playerIds         = "is"
//   val binaryPieces      = "ps"
//   val oldPgn            = "pg"
//   val huffmanPgn        = "hp"
//   val status            = "s"
//   val startedAtTurn     = "st"
//   val clock             = "c"
//   val positionHashes    = "ph"
//   val checkCount        = "cc"
//   val castleLastMove    = "cl"
//   val unmovedRooks      = "ur"
//   val daysPerTurn       = "cd"
//   val moveTimes         = "mt"
//   val whiteClockHistory = "cw"
//   val blackClockHistory = "cb"
//   val rated             = "ra"
//   val variant           = "v"
//   val crazyData         = "chd"
//   val bookmarks         = "bm"
//   val source            = "so"
//   val tournamentId      = "tid"
//   val swissId           = "iid"
//   val simulId           = "sid"
//   val tvAt              = "tv"
//   val winnerColor       = "w"
//   val initialFen        = "if"
//   val checkAt           = "ck"
//   val drawOffers        = "do"
//   val rules             = "rules"

object DbGame:

  given Decoder[DbGame] =
    Decoder.forProduct22(
      "_id",
      "us",
      "wid",
      "ca",
      "ua",
      "t",
      "an",
      "pl",
      "p0",
      "p1",
      "is",
      "ps",
      "hp",
      "s",
      "c",
      "mt",
      "cw",
      "cb",
      "ra",
      "v",
      "so",
      "w"
    )(DbGame.apply)

  given Encoder[DbGame] =
    Encoder.forProduct22(
      "_id",
      "us",
      "wid",
      "ca",
      "ua",
      "t",
      "an",
      "pl",
      "p0",
      "p1",
      "is",
      "ps",
      "hp",
      "s",
      "c",
      "mt",
      "cw",
      "cb",
      "ra",
      "v",
      "so",
      "w"
    )(g =>
      (
        g.id,
        g.players,
        g.winnerId,
        g.createdAt,
        g.moveAt,
        g.turn,
        g.analysed,
        g.playingUids,
        g.whitePlayer,
        g.blackPlayer,
        g.playerIds,
        g.binaryPieces,
        g.huffmanPgn,
        g.status,
        g.encodedClock,
        g.moveTimes,
        g.encodedWhiteClock,
        g.encodedBlackClock,
        g.rated,
        g.variant,
        g.source,
        g.winnerColor
      )
    )

case class DbPlayer(
    rating: Option[Int],
    ratingDiff: Option[Int],
    berserk: Option[Boolean],
    aiLevel: Option[Int],
    provisional: Option[Boolean],
    name: Option[String]
):
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
