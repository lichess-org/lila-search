package lila.search
package ingestor

import chess.variant.Variant
import chess.{ Speed, Status }
import lila.search.es.*

object Translate:

  def game(g: DbGame, botIds: Set[String]): GameSource =
    val whiteUser = g.whiteId.getOrElse("")
    val blackUser = g.blackId.getOrElse("")
    val whiteRating = g.whitePlayer.flatMap(_.rating).getOrElse(0)
    val blackRating = g.blackPlayer.flatMap(_.rating).getOrElse(0)
    GameSource(
      status = g.status,
      turns = (g.ply + 1) / 2,
      rated = g.rated.getOrElse(false),
      perf = perfId(g.variantOrDefault, g.speed),
      winnerColor = g.winnerColor match
        case Some(true) => 1 // White
        case Some(false) => 2 // Black
        case None =>
          if g.status > Status.Stalemate.id && g.status != Status.UnknownFinish.id then 3 // Draw
          else 0, // Unknown
      date = g.date.getEpochSecond,
      analysed = g.analysed.getOrElse(false),
      averageRating = if whiteRating > 0 && blackRating > 0 then (whiteRating + blackRating) / 2 else 0,
      ai = g.aiLevel.getOrElse(0),
      duration = durationSeconds(g),
      // we set clockInit to -1 for games without clock config to distinguish them from games with clock config but 0 initial time
      clockInit = g.clockInit.getOrElse(-1),
      // similar to clockInit
      clockInc = g.clockInc.getOrElse(-1),
      whiteUser = whiteUser,
      blackUser = blackUser,
      source = g.source.getOrElse(0),
      whiteRating = whiteRating,
      blackRating = blackRating,
      chess960Pos = g.chess960Position.getOrElse(1000),
      whiteBot = whiteUser.nonEmpty && botIds.contains(whiteUser),
      blackBot = blackUser.nonEmpty && botIds.contains(blackUser)
    )

  // Helper: calculate game duration in seconds
  private def durationSeconds(g: DbGame): Int =
    // If there is no clock config, it means it's either a very old game or a correspondence game
    if g.clockConfig.isEmpty then 0
    else
      val seconds = g.date.getEpochSecond - g.createdAt.getEpochSecond
      if seconds <= 0 then 0
      else if seconds < 60 * 60 * 12 then seconds.toInt
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

  def study(study: DbStudy, chapters: Option[List[StudyChapterData]]): StudySource =
    StudySource(
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
