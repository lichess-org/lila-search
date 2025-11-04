package lila.search
package ingestor

import cats.syntax.all.*
import chess.Speed
import chess.variant.Variant
import mongo4cats.bson.Document

// Pure translation functions from MongoDB models to Smithy models
object Translate:

  // Pure function: DbGame => GameSource
  def game(g: DbGame): GameSource =
    GameSource(
      status = g.status,
      turns = (g.ply + 1) / 2,
      rated = g.rated.getOrElse(false),
      perf = perfId(g.variantOrDefault, g.speed),
      winnerColor = g.winnerColor.fold(3)(if _ then 1 else 2),
      date = SearchDateTime.fromInstant(g.movedAt),
      analysed = g.analysed.getOrElse(false),
      uids = g.players.some, // make uids not optional
      winner = g.winnerId,
      loser = g.loser,
      averageRating = averageUsersRating(g),
      ai = g.aiLevel,
      duration = durationSeconds(g),
      clockInit = g.clockInit,
      clockInc = g.clockInc,
      whiteUser = g.whiteId,
      blackUser = g.blackId,
      source = g.source
    )

  // Helper: calculate average users rating
  private def averageUsersRating(g: DbGame): Option[Int] =
    List(g.whitePlayer.flatMap(_.rating), g.blackPlayer.flatMap(_.rating)).flatten match
      case a :: b :: Nil => Some((a + b) / 2)
      case a :: Nil => Some((a + 1500) / 2)
      case _ => None

  // Helper: calculate game duration in seconds
  private def durationSeconds(g: DbGame): Option[Int] =
    val seconds = (g.movedAt.toEpochMilli / 1000 - g.createdAt.toEpochMilli / 1000)
    Option.when(seconds < 60 * 60 * 12)(seconds.toInt)

  // Helper: determine perf type based on variant and speed
  private def perfId(variant: Variant, speed: Speed): Int =
    import chess.variant.*
    variant.match
      case Standard | FromPosition =>
        speed match
          case Speed.UltraBullet => 0
          case Speed.Bullet => 1
          case Speed.Blitz => 2
          case Speed.Rapid => 6
          case Speed.Classical => 3
          case Speed.Correspondence => 4
      case Crazyhouse => 18
      case Chess960 => 11
      case KingOfTheHill => 12
      case ThreeCheck => 15
      case Antichess => 13
      case Atomic => 14
      case Horde => 16
      case RacingKings => 17

  // Pure function for forum: DbForum => ForumSource
  def forum(forum: DbForum): ForumSource =
    ForumSource(
      body = forum.post.text,
      topic = forum.topicName,
      topicId = forum.post.topicId,
      author = forum.post.userId.some,
      troll = forum.post.troll,
      date = forum.post.createdAt.toEpochMilli
    )

  // Pure function for study: (Document, StudyData) => Option[StudySource]
  def study(doc: Document, data: StudyData): Option[StudySource] =
    (
      doc.getString("name"),
      doc.getString("ownerId"),
      data.chapterNames.some,
      data.chapterTexts.some
    ).mapN: (name, ownerId, chapterNames, chapterTexts) =>
      val members = doc.getDocument("members").fold(Nil)(_.toMap.keys.toList)
      val topics = doc.getList("topics").map(_.flatMap(_.asString)).getOrElse(Nil)
      val likes = doc.getInt("likes").getOrElse(0)
      val isPublic = doc.getString("visibility").map(_ == "public").getOrElse(true)
      val rank = doc.get("rank").flatMap(_.asInstant).map(SearchDateTime.fromInstant)
      val createdAt = doc.get("createdAt").flatMap(_.asInstant).map(SearchDateTime.fromInstant)
      val updatedAt = doc.get("updatedAt").flatMap(_.asInstant).map(SearchDateTime.fromInstant)
      StudySource(
        name,
        ownerId,
        members,
        chapterNames,
        chapterTexts,
        likes,
        isPublic,
        topics,
        rank,
        createdAt,
        updatedAt
      )

  // Pure function for team: DbTeam => TeamSource
  def team(team: DbTeam): TeamSource =
    TeamSource(team.name, team.description, team.nbMembers)

  // Pure function for ublog: Document => Option[UblogSource]
  def ublog(doc: Document): Option[UblogSource] =
    for
      title <- doc.getString("title")
      intro <- doc.getString("intro")
      body <- doc.getString("markdown")
      author <- doc.getString("blog").map(_.split(":")(1))
      language <- doc.getString("language")
      likes <- doc.getAs[Int]("likes")
      topics <- doc.getAs[List[String]]("topics").map(_.mkString(" ").replaceAll("Chess", ""))
      text = s"$title\n$topics\n$author\n$intro\n$body"
      date <- doc.getNested("lived.at").flatMap(_.asInstant).map(_.toEpochMilli)
      quality = doc.getNestedAs[Int]("automod.quality")
    yield UblogSource(text, language, likes, date, quality)
