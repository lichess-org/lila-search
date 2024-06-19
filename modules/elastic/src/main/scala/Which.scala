package lila.search

object Which:

  def mapping(index: Index) =
    index.value match
      case "game"  => Some(game.Mapping.fields)
      case "forum" => Some(forum.Mapping.fields)
      case "team"  => Some(team.Mapping.fields)
      case "study" => Some(study.Mapping.fields)
      case _       => None

  def refreshInterval(index: Index) =
    index.value match
      case "study" => "10s"
      case _       => "300s"
