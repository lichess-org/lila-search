package lila.search

/**
 * Maps lichess-detected language codes (ISO 639-1, as stored on forum posts)
 * to the language analyzers Elasticsearch 7.10 ships built-in.
 * Codes with no built-in analyzer are unsupported: their posts are only
 * searchable through the standard-analyzed fallback fields.
 */
object SearchLang:

  val byCode: Map[String, String] = Map(
    "ar" -> "arabic",
    "hy" -> "armenian",
    "eu" -> "basque",
    "bn" -> "bengali",
    "bg" -> "bulgarian",
    "ca" -> "catalan",
    "zh" -> "cjk",
    "ja" -> "cjk",
    "ko" -> "cjk",
    "cs" -> "czech",
    "da" -> "danish",
    "nl" -> "dutch",
    "en" -> "english",
    "et" -> "estonian",
    "fi" -> "finnish",
    "fr" -> "french",
    "gl" -> "galician",
    "de" -> "german",
    "el" -> "greek",
    "hi" -> "hindi",
    "hu" -> "hungarian",
    "id" -> "indonesian",
    "ga" -> "irish",
    "it" -> "italian",
    "lv" -> "latvian",
    "lt" -> "lithuanian",
    "no" -> "norwegian",
    "nb" -> "norwegian",
    "nn" -> "norwegian",
    "fa" -> "persian",
    "pt" -> "portuguese",
    "ro" -> "romanian",
    "ru" -> "russian",
    "ckb" -> "sorani",
    "es" -> "spanish",
    "sv" -> "swedish",
    "th" -> "thai",
    "tr" -> "turkish"
  )

  val sortedCodes: List[(String, String)] = byCode.toList.sortBy(_._1)

  def esLangKey(code: Option[String]): Option[String] = code.filter(byCode.contains)
