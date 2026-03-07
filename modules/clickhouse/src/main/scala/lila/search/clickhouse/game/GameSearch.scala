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
    (fr"SELECT id FROM games FINAL" ++ whereClause(q) ++ orderClause(q.sorting) ++
      fr"LIMIT ${size.value} OFFSET ${from.value}")
      .query[String]
      .to[List]

  def count(q: Game): ConnectionIO[Long] =
    (fr"SELECT count() FROM games FINAL" ++ whereClause(q))
      .query[Long]
      .unique

  private def whereClause(q: Game): Fragment =
    Fragments.whereAndOpt(filters(q))

  private def filters(q: Game): List[Fragment] =
    val userFilters = List(q.user1, q.user2).flatten.map(u => fr"(white_user = $u OR black_user = $u)")
    val perfFilter = q.perf.toNel.map(perfs => Fragments.in(fr"perf", perfs))
    val hasAiFilter = q.hasAi.map(a => if a then fr"ai_level != 0" else fr"ai_level = 0")
    val aiLevelFilters =
      if q.hasAi.getOrElse(true) then rangeFilters(Columns.aiLevel, q.aiLevel.a, q.aiLevel.b)
      else Nil

    userFilters :::
      List(
        q.winner.map(w =>
          fr"((winner_color = 1 AND white_user = $w) OR (winner_color = 2 AND black_user = $w))"
        ),
        q.loser.map(l =>
          fr"((winner_color = 2 AND white_user = $l) OR (winner_color = 1 AND black_user = $l))"
        ),
        q.winnerColor.map(c => fr"winner_color = $c"),
        perfFilter,
        q.source.map(s => fr"source = $s"),
        q.status.map(s => fr"status = $s"),
        q.rated.map(r => fr"rated = $r"),
        q.analysed.map(a => fr"analysed = $a"),
        q.whiteUser.map(u => fr"white_user = $u"),
        q.blackUser.map(u => fr"black_user = $u"),
        hasAiFilter,
        q.clockInit.map(c => fr"clock_init = $c"),
        q.clockInc.map(c => fr"clock_inc = $c")
      ).flatten :::
      rangeFilters(Columns.turns, q.turns.a, q.turns.b) :::
      ratingFilters(q.averageRating.a, q.averageRating.b) :::
      rangeFilters(Columns.date, q.date.a, q.date.b) :::
      rangeFilters(Columns.duration, q.duration.a, q.duration.b) :::
      aiLevelFilters

  // ClickHouse column expressions used in ORDER BY and range filters.
  // Defined as Fragment.const constants so they cannot be confused with user input.
  private object Columns:
    val date = Fragment.const("date")
    val turns = Fragment.const("turns")
    val averageRating = Fragment.const("(white_rating + black_rating) / 2")
    val aiLevel = Fragment.const("ai_level")
    val duration = Fragment.const("duration")

  // When filtering by average rating, exclude games where either player has no rating (stored as 0).
  // This matches ES behavior where averageRating is None for such games, so they never match range filters.
  private def ratingFilters(min: Option[Int], max: Option[Int]): List[Fragment] =
    if min.isEmpty && max.isEmpty then Nil
    else fr"white_rating > 0 AND black_rating > 0" :: rangeFilters(Columns.averageRating, min, max)

  private def rangeFilters[A: Put](col: Fragment, min: Option[A], max: Option[A]): List[Fragment] =
    List(
      min.map(v => col ++ fr">= $v"),
      max.map(v => col ++ fr"<= $v")
    ).flatten

  private def orderClause(s: Sorting): Fragment =
    val col = s.f match
      case Fields.date => Columns.date
      case Fields.turns => Columns.turns
      case Fields.averageRating => Columns.averageRating
      case _ => Columns.date
    val dir = Fragment.const(if s.order.equalsIgnoreCase("asc") then "ASC" else "DESC")
    fr"ORDER BY" ++ col ++ dir
