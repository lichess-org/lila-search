$version: "2"

namespace lila.search.ingestor

use smithy.api#default
use smithy.api#jsonName

use lila.search.core#Ids
use lila.search.core#FromInt
use lila.search.core#SizeInt
use lila.search.core#PlayerIds
use lila.search.core#Strings
use lila.search.core#IndexString
use lila.search.core#DateTime

use lila.search.es#textField
use lila.search.es#keywordField
use lila.search.es#dateField
use lila.search.es#shortField
use lila.search.es#intField
use lila.search.es#booleanField

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
  @keywordField(docValues: false)
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
  @keywordField(docValues: false)
  perf: Integer
  @jsonName("u")
  @keywordField(docValues: false)
  uids: PlayerIds
  @jsonName("w")
  @keywordField(docValues: false)
  winner: String
  @jsonName("o")
  @keywordField(docValues: false)
  loser: String
  @required
  @jsonName("c")
  @keywordField(docValues: false)
  winnerColor: Integer
  @jsonName("a")
  @shortField(docValues: true)
  averageRating: Integer
  @jsonName("i")
  @shortField(docValues: false)
  ai: Integer
  @required
  @jsonName("d")
  @dateField(format: "yyyy-MM-dd HH:mm:ss", docValues: true)
  date: DateTime
  @jsonName("l")
  @intField(docValues: false)
  duration: Integer
  @jsonName("ct")
  @intField(docValues: false)
  clockInit: Integer
  @jsonName("ci")
  @shortField(docValues: false)
  clockInc: Integer
  @required
  @jsonName("n")
  @booleanField(docValues: false)
  analysed: Boolean
  @jsonName("wu")
  @keywordField(docValues: false)
  whiteUser: String
  @jsonName("bu")
  @keywordField(docValues: false)
  blackUser: String
  @jsonName("so")
  @keywordField(docValues: false)
  source: Integer
}

structure StudySource {
  @required
  @textField(boost: 10, analyzer: "english", keywordSubfield: {name: "raw"})
  name: String
  @required
  @keywordField(boost: 2, docValues: false)
  owner: String
  @required
  @keywordField(boost: 1, docValues: false)
  members: PlayerIds
  @required
  @textField(boost: 4, analyzer: "english")
  chapterNames: String
  @required
  @textField(boost: 1, analyzer: "english")
  chapterTexts: String
  @default
  @textField(boost: 5, analyzer: "english")
  topics: Strings
  @required
  @shortField(docValues: true)
  likes: Integer
  @required
  @booleanField(docValues: false)
  public: Boolean
  @dateField(format: "yyyy-MM-dd HH:mm:ss")
  rank: DateTime
  @jsonName("createdAt_date")
  @dateField(format: "yyyy-MM-dd HH:mm:ss")
  createdAt: DateTime
  @jsonName("updatedAt_date")
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
