package lila.search

object Mapping {

  def apply(index: Index) = index match {
    case Index("game")  => Some(Game.mapping)
    case Index("forum") => Some(Forum.mapping)
    case Index("team")  => Some(Team.mapping)
    case _              => None
  }
}
