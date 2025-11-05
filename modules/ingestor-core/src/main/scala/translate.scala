package lila.search
package ingestor

import cats.syntax.all.*
import chess.Speed
import chess.variant.Variant

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

  // Pure function for study: (DbStudy, StudyChapterData) => StudySource
  def study(study: DbStudy, data: StudyChapterData): StudySource =
    StudySource(
      name = study.name,
      owner = study.ownerId,
      members = study.memberIds,
      chapterNames = data.chapterNames,
      chapterTexts = data.chapterTexts,
      likes = study.likes.getOrElse(0),
      public = study.visibility.fold(true)(_ == "public"),
      topics = study.topics.getOrElse(Nil),
      rank = study.rank.map(SearchDateTime.fromInstant),
      createdAt = study.createdAt.map(SearchDateTime.fromInstant),
      updatedAt = study.updatedAt.map(SearchDateTime.fromInstant)
    )

  // Pure function for team: DbTeam => TeamSource
  def team(team: DbTeam): TeamSource =
    TeamSource(team.name, team.description, team.nbMembers)

  // Pure function for ublog: DbUblog => UblogSource
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
