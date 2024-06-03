package lila.search

object Which:

  def mapping(index: Index) =
    index match
      case Index("game")  => Some(game.Mapping.fields)
      case Index("forum") => Some(forum.Mapping.fields)
      case Index("team")  => Some(team.Mapping.fields)
      case Index("study") => Some(study.Mapping.fields)
      case _              => None

  def refreshInterval(index: Index) =
    index match
      case Index("study") => "10s"
      case _              => "300s"
