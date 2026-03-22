package lila.search
package clickhouse.game

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import lila.search.game.{ Fields, Game, Sorting }
import lila.search.{ From, Size }

object GameSearch:
  import GameRow.given

  def search(q: Game, from: From, size: Size): ConnectionIO[List[String]] =
    if hasUserFilter(q) then userSearch(q, from, size)
    else mainSearch(q, from, size)

  def count(q: Game): ConnectionIO[Long] =
    if hasUserFilter(q) then userCount(q)
    else mainCount(q)

  private def hasUserFilter(q: Game): Boolean =
    q.user1.isDefined || q.user2.isDefined ||
      q.winner.isDefined || q.loser.isDefined ||
      q.whiteUser.isDefined || q.blackUser.isDefined

  // --- games_by_user table (fast path for user queries) ---

  private def userSearch(q: Game, from: From, size: Size): ConnectionIO[List[String]] =
    (fr"SELECT DISTINCT id FROM games_by_user" ++ userWhereClause(q) ++ orderClause(q.sorting, UserColumns) ++
      fr"LIMIT ${size.value} OFFSET ${from.value}")
      .query[String]
      .to[List]

  private def userCount(q: Game): ConnectionIO[Long] =
    (fr"SELECT count(DISTINCT id) FROM games_by_user" ++ userWhereClause(q))
      .query[Long]
      .unique

  private def userWhereClause(q: Game): Fragment =
    Fragments.whereAndOpt(userFilters(q))

  private def userFilters(q: Game): List[Fragment] =
    // Determine the primary user for the `user` column lookup.
    // winner/loser/whiteUser/blackUser all imply a specific user when used alone,
    // but when combined with user1, they become game-level conditions instead.
    val primaryUser =
      q.user1.orElse(q.user2).orElse(q.winner).orElse(q.loser).orElse(q.whiteUser).orElse(q.blackUser)

    val userLookup = primaryUser.map(u => fr"user = $u").toList

    // user1 + user2 = games between them
    val opponentFilter =
      if q.user1.isDefined && q.user2.isDefined then q.user2.map(u2 => fr"opponent = $u2")
      else None

    // whiteUser/blackUser: constrain color when they are the primary user
    val colorFilter =
      if q.whiteUser.isDefined && q.whiteUser == primaryUser then Some(fr"color = 1")
      else if q.blackUser.isDefined && q.blackUser == primaryUser then Some(fr"color = 2")
      else None

    // Winner filter: user W won the game.
    // If W is the primary user: constrain by "this user won" (color matches winner_color)
    // If W is a different user (e.g., user1 is set): constrain by opponent name + opponent won
    val winnerFilter = q.winner.map: w =>
      if q.user1.isDefined && q.user1.exists(_ != w) then
        // Querying from user1's perspective, winner is the opponent
        fr"opponent = $w AND ((color = 1 AND winner_color = 2) OR (color = 2 AND winner_color = 1))"
      else fr"((color = 1 AND winner_color = 1) OR (color = 2 AND winner_color = 2))"

    // Loser filter: user L lost the game.
    val loserFilter = q.loser.map: l =>
      if q.user1.isDefined && q.user1.exists(_ != l) then
        // Querying from user1's perspective, loser is the opponent
        fr"opponent = $l AND ((color = 1 AND winner_color = 1) OR (color = 2 AND winner_color = 2))"
      else fr"((color = 1 AND winner_color = 2) OR (color = 2 AND winner_color = 1))"

    val perfFilter = q.perf.toNel.map(perfs => Fragments.in(fr"perf", perfs))
    val hasAiFilter = q.hasAi.map(a => if a then fr"ai_level != 0" else fr"ai_level = 0")
    val aiLevelFilters =
      if q.hasAi.getOrElse(true) then rangeFilters(UserColumns.aiLevel, q.aiLevel.a, q.aiLevel.b)
      else Nil

    userLookup :::
      List(
        opponentFilter,
        colorFilter,
        winnerFilter,
        loserFilter,
        q.winnerColor.map(c => fr"winner_color = $c"),
        perfFilter,
        q.source.map(s => fr"source = $s"),
        q.status.map(s => fr"status = $s"),
        q.rated.map(r => fr"rated = $r"),
        q.analysed.map(a => fr"analysed = $a"),
        hasAiFilter,
        q.clockInit.map(c => fr"clock_init = $c"),
        q.clockInc.map(c => fr"clock_inc = $c")
      ).flatten :::
      rangeFilters(UserColumns.turns, q.turns.a, q.turns.b) :::
      avgRatingFilters(q.averageRating.a, q.averageRating.b) :::
      rangeFilters(UserColumns.date, q.date.a, q.date.b) :::
      rangeFilters(UserColumns.duration, q.duration.a, q.duration.b) :::
      aiLevelFilters

  private trait Columns:
    val date: Fragment = Fragment.const("date")
    val turns: Fragment = Fragment.const("turns")
    def averageRating: Fragment
    val aiLevel: Fragment = Fragment.const("ai_level")
    val duration: Fragment = Fragment.const("duration")

  private object UserColumns extends Columns:
    val averageRating = Fragment.const("avg_rating")

  private def avgRatingFilters(min: Option[Int], max: Option[Int]): List[Fragment] =
    if min.isEmpty && max.isEmpty then Nil
    else fr"avg_rating > 0" :: rangeFilters(UserColumns.averageRating, min, max)

  // --- main games table (fallback for non-user queries) ---

  private def mainSearch(q: Game, from: From, size: Size): ConnectionIO[List[String]] =
    (fr"SELECT id FROM games" ++ mainWhereClause(q) ++ orderClause(q.sorting, MainColumns) ++
      fr"LIMIT ${size.value} OFFSET ${from.value}")
      .query[String]
      .to[List]

  private def mainCount(q: Game): ConnectionIO[Long] =
    (fr"SELECT count() FROM games" ++ mainWhereClause(q))
      .query[Long]
      .unique

  private def mainWhereClause(q: Game): Fragment =
    Fragments.whereAndOpt(mainFilters(q))

  private def mainFilters(q: Game): List[Fragment] =
    val perfFilter = q.perf.toNel.map(perfs => Fragments.in(fr"perf", perfs))
    val hasAiFilter = q.hasAi.map(a => if a then fr"ai_level != 0" else fr"ai_level = 0")
    val aiLevelFilters =
      if q.hasAi.getOrElse(true) then rangeFilters(MainColumns.aiLevel, q.aiLevel.a, q.aiLevel.b)
      else Nil

    List(
      q.winnerColor.map(c => fr"winner_color = $c"),
      perfFilter,
      q.source.map(s => fr"source = $s"),
      q.status.map(s => fr"status = $s"),
      q.rated.map(r => fr"rated = $r"),
      q.analysed.map(a => fr"analysed = $a"),
      hasAiFilter,
      q.clockInit.map(c => fr"clock_init = $c"),
      q.clockInc.map(c => fr"clock_inc = $c")
    ).flatten :::
      rangeFilters(MainColumns.turns, q.turns.a, q.turns.b) :::
      avgRatingFilters(q.averageRating.a, q.averageRating.b) :::
      rangeFilters(MainColumns.date, q.date.a, q.date.b) :::
      rangeFilters(MainColumns.duration, q.duration.a, q.duration.b) :::
      aiLevelFilters

  private object MainColumns extends Columns:
    val averageRating = Fragment.const("avg_rating")

  // --- shared helpers ---

  private def rangeFilters[A: Put](col: Fragment, min: Option[A], max: Option[A]): List[Fragment] =
    List(
      min.map(v => col ++ fr">= $v"),
      max.map(v => col ++ fr"<= $v")
    ).flatten

  private def orderClause(s: Sorting, cols: Columns): Fragment =
    val col = s.f match
      case Fields.date => cols.date
      case Fields.turns => cols.turns
      case Fields.averageRating => cols.averageRating
      case _ => cols.date
    val dir = Fragment.const(if s.order.equalsIgnoreCase("asc") then "ASC" else "DESC")
    fr"ORDER BY" ++ col ++ dir
