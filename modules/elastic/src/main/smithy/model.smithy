$version: "2"

namespace lila.search.es

use smithy.api#default
use smithy.api#jsonName

use lila.search.core#Ids
use lila.search.core#FromInt
use lila.search.core#SizeInt
use lila.search.core#PlayerIds
use lila.search.core#Strings
use lila.search.core#IndexString
use lila.search.core#DateTime

structure ForumSource {
  @required
  @jsonName("bo")
  @textField(boost: 2, analyzer: "english")
  body: String
  @required
  @jsonName("to")
  @textField(boost: 5, analyzer: "english")
  topic: String
  @required
  @jsonName("ti")
  @keywordField(docValues: false)
  topicId: String
  @jsonName("au")
  @keywordField(docValues: false)
  author: String
  @required
  @jsonName("tr")
  @booleanField(docValues: false)
  troll: Boolean
  /// time in milliseconds
  @required
  @jsonName("da")
  @dateField
  date: Long
}

structure GameSource {
  @required
  @jsonName("s")
  @byteField(docValues: false)
  status: Integer
  @required
  @jsonName("t")
  @shortField(docValues: true)
  turns: Integer
  @required
  @jsonName("r")
  @booleanField(docValues: false)
  rated: Boolean
  @required
  @jsonName("p")
  @byteField(docValues: false)
  perf: Integer
  @required
  @jsonName("c")
  @byteField(docValues: false)
  winnerColor: Integer
  @required
  @jsonName("a")
  @shortField(docValues: true)
  averageRating: Integer
  @required
  @jsonName("i")
  @byteField(docValues: false)
  ai: Integer
  @required
  @jsonName("d")
  @dateField(format: "yyyy-MM-dd HH:mm:ss", docValues: true)
  date: DateTime
  @required
  @jsonName("l")
  @intField(docValues: false)
  duration: Integer
  @required
  @jsonName("ct")
  @shortField(docValues: false)
  clockInit: Integer
  @required
  @jsonName("ci")
  @shortField(docValues: false)
  clockInc: Integer
  @required
  @jsonName("n")
  @booleanField(docValues: false)
  analysed: Boolean
  @required
  @jsonName("wu")
  @keywordField(docValues: false)
  whiteUser: String
  @required
  @jsonName("bu")
  @keywordField(docValues: false)
  blackUser: String
  @required
  @jsonName("so")
  @byteField(docValues: false)
  source: Integer
  @required
  @jsonName("wr")
  @shortField(docValues: false)
  whiteRating: Integer
  @required
  @jsonName("br")
  @shortField(docValues: false)
  blackRating: Integer
  @required
  @jsonName("c9")
  @shortField(docValues: false)
  chess960Pos: Integer
  @required
  @jsonName("wb")
  @booleanField(docValues: false)
  whiteBot: Boolean
  @required
  @jsonName("bb")
  @booleanField(docValues: false)
  blackBot: Boolean
}

structure ChapterTags {
  @keywordField(docValues: false)
  variant: String
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms")
  event: String
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms")
  white: String
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms")
  black: String
  @keywordField(docValues: false)
  whiteFideId: String
  @keywordField(docValues: false)
  blackFideId: String
  @keywordField(docValues: false)
  eco: String
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms")
  opening: String
}

structure Chapter {
  @required
  @keywordField(docValues: false)
  id: String
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms")
  name: String
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms")
  description: String
  @nestedField(dynamic: "false")
  tags: ChapterTags
}

list Chapters {
  member: Chapter
}

structure StudySource {
  @required
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms", keywordSubfield: {name: "raw", normalizer: "lowercase"})
  name: String
  @textField( analyzer: "english", searchAnalyzer: "english_with_chess_synonyms" )
  description: String
  @required
  @keywordField(docValues: false)
  owner: String
  @required
  @keywordField(docValues: false)
  members: PlayerIds
  @default
  @textField(analyzer: "english", searchAnalyzer: "english_with_chess_synonyms", docValues: false)
  topics: Strings
  @nestedField(dynamic: "false")
  chapters: Chapters
  @required
  @intField
  likes: Integer
  @required
  @booleanField
  public: Boolean
  @dateField(format: "yyyy-MM-dd HH:mm:ss")
  rank: DateTime
  @dateField(format: "yyyy-MM-dd HH:mm:ss")
  createdAt: DateTime
  @dateField(format: "yyyy-MM-dd HH:mm:ss")
  updatedAt: DateTime
}

structure TeamSource {
  @required
  @jsonName("na")
  @textField(boost: 10, analyzer: "english")
  name: String
  @required
  @jsonName("de")
  @textField(boost: 2, analyzer: "english")
  description: String
  @required
  @jsonName("nbm")
  @shortField
  nbMembers: Integer
}

structure UblogSource {
  @required
  @textField
  text: String
  @required
  @keywordField(docValues: false)
  language: String
  @required
  @shortField(docValues: true)
  likes: Integer
  @required
  @dateField(docValues: true)
  date: Long
  @shortField(docValues: true)
  quality: Integer
}
