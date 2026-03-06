package lila.search
package ingestor

import cats.syntax.all.*
import chess.variant.Variant
import chess.{ Speed, Status }
import lila.search.es.*

object Translate:

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
      duration = durationSeconds(g).some,
      clockInit = g.clockInit,
      clockInc = g.clockInc,
      whiteUser = g.whiteId,
      blackUser = g.blackId,
      source = g.source
    )

  import lila.search.clickhouse.game.{ GameRow, WinnerColor }
  def toGameRow(g: DbGame, botIds: Set[String]): GameRow =
    val whiteUser = g.whiteId.getOrElse("")
    val blackUser = g.blackId.getOrElse("")
    GameRow(
      id = g.id,
      status = g.status,
      turns = (g.ply + 1) / 2,
      rated = g.rated.getOrElse(false),
      perf = perfId(g.variantOrDefault, g.speed),
      winnerColor = g.winnerColor match
        case Some(true) => WinnerColor.White
        case Some(false) => WinnerColor.Black
        case None =>
          // If the game is not finished, we set it to unknown
          // If the game is finished and there is no winner, it means it's a draw except when the status is UnknownFinish
          if g.status > Status.Stalemate.id && g.status != Status.UnknownFinish.id then WinnerColor.Draw
          else WinnerColor.Unknown,
      date = g.movedAt,
      analysed = g.analysed.getOrElse(false),
      whiteRating = g.whitePlayer.flatMap(_.rating).getOrElse(0),
      blackRating = g.blackPlayer.flatMap(_.rating).getOrElse(0),
      aiLevel = g.aiLevel.getOrElse(0),
      duration = durationSeconds(g),
      clockInit = g.clockInit,
      clockInc = g.clockInc,
      whiteUser = whiteUser,
      blackUser = blackUser,
      source = g.source,
      chess960Position = g.chess960Position.getOrElse(1000),
      whiteBot = whiteUser.nonEmpty && botIds.contains(whiteUser),
      blackBot = blackUser.nonEmpty && botIds.contains(blackUser)
    )

  // Helper: calculate average users rating
  private def averageUsersRating(g: DbGame): Option[Int] =
    List(g.whitePlayer.flatMap(_.rating), g.blackPlayer.flatMap(_.rating)).flatten match
      case a :: b :: Nil => Some((a + b) / 2)
      case a :: Nil => Some((a + 1500) / 2)
      case _ => None

  // Helper: calculate game duration in seconds
  private def durationSeconds(g: DbGame): Int =
    // If there is no clock config, it means it's either a very old game or a correspondence game
    if g.clockConfig.isEmpty then 0
    else
      val seconds = (g.movedAt.toEpochMilli / 1000 - g.createdAt.toEpochMilli / 1000)
      if seconds < 60 * 60 * 12 then seconds.toInt
      else 60 * 60 * 12 + 1 // cap duration to 12 hours + 1 seconds for very long games

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

  def forum(forum: DbForum): ForumSource =
    ForumSource(
      body = forum.post.text,
      topic = forum.topicName,
      topicId = forum.post.topicId,
      author = forum.post.userId,
      troll = forum.post.troll,
      date = forum.post.createdAt.toEpochMilli
    )

  def study(study: DbStudy, chapters: Option[List[StudyChapterData]]): Study2Source =
    Study2Source(
      name = study.name,
      description = study.description.filterNot(s => s.isBlank || s == "-"),
      owner = study.ownerId,
      members = study.memberIds,
      topics = study.topics.getOrElse(Nil),
      chapters = chapters.map(_.map(translateChapter)),
      likes = study.likes.getOrElse(0),
      public = study.visibility.fold(false)(_ == "public"),
      rank = study.rank.map(SearchDateTime.fromInstant),
      createdAt = study.createdAt.map(SearchDateTime.fromInstant),
      updatedAt = study.updatedAt.map(SearchDateTime.fromInstant)
    )

  private def translateChapter(chapter: StudyChapterData): es.Chapter =
    es.Chapter(
      id = chapter.id,
      name = Some(chapter.name).filterNot(_.isBlank),
      description = chapter.description.filterNot(_.isBlank),
      tags = if chapter.tags.isEmpty then None else Some(translateChapterTags(chapter.tags))
    )

  private def translateChapterTags(tags: List[chess.format.pgn.Tag]): es.ChapterTags =
    import chess.format.pgn.Tag
    val tagMap = tags.map(t => t.name.toString -> t.value).toMap
    es.ChapterTags(
      variant = tagMap.get(Tag.Variant.toString),
      event = tagMap.get(Tag.Event.toString),
      white = tagMap.get(Tag.White.toString),
      black = tagMap.get(Tag.Black.toString),
      whiteFideId = tagMap.get(Tag.WhiteFideId.toString),
      blackFideId = tagMap.get(Tag.BlackFideId.toString),
      eco = tagMap.get(Tag.ECO.toString),
      opening = tagMap.get(Tag.Opening.toString)
    )

  def team(team: DbTeam): TeamSource =
    TeamSource(team.name, team.description, team.nbMembers)

  // todo maybe return Option[UblogSource] and filter out low quality blogs or non-live?
  def ublog(ublog: DbUblog): UblogSource =
    val topics = ublog.topics.mkString(" ").replaceAll("Chess", "")
    val text = s"${ublog.title}\n$topics\n${ublog.author}\n${ublog.intro}\n${ublog.markdown}"
    UblogSource(
      text = text,
      language = ublog.language,
      likes = ublog.likes,
      date = ublog.livedAt.fold(0L)(_.toEpochMilli),
      quality = ublog.quality
    )
