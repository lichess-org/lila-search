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

structure ForumSource {
  @required
  @jsonName("bo")
  body: String
  @required
  @jsonName("to")
  topic: String
  @required
  @jsonName("ti")
  topicId: String
  @jsonName("au")
  author: String
  @required
  @jsonName("tr")
  troll: Boolean
  /// time in milliseconds
  @required
  @jsonName("da")
  date: Long
}

structure GameSource {
  @required
  @jsonName("s")
  status: Integer
  @required
  @jsonName("t")
  turns: Integer
  @required
  @jsonName("r")
  rated: Boolean
  @required
  @jsonName("p")
  perf: Integer
  @jsonName("u")
  uids: PlayerIds
  @jsonName("w")
  winner: String
  @jsonName("o")
  loser: String
  @required
  @jsonName("c")
  winnerColor: Integer
  @jsonName("a")
  averageRating: Integer
  @jsonName("i")
  ai: Integer
  @required
  @jsonName("d")
  date: DateTime
  @jsonName("l")
  duration: Integer
  @jsonName("ct")
  clockInit: Integer
  @jsonName("ci")
  clockInc: Integer
  @required
  @jsonName("n")
  analysed: Boolean
  @jsonName("wu")
  whiteUser: String
  @jsonName("bu")
  blackUser: String
  @jsonName("so")
  source: Integer
}

structure StudySource {
  @required
  name: String
  @required
  owner: String
  @required
  members: PlayerIds
  @required
  chapterNames: String

  @required
  chapterTexts: String
  @default
  topics: Strings
  @required
  likes: Integer
  @required
  public: Boolean
}

structure TeamSource {
  @required
  @jsonName("na")
  name: String
  @required
  @jsonName("de")
  description: String
  @required
  @jsonName("nbm")
  nbMembers: Integer
}

structure UblogSource {
  @required
  text: String
  @required
  language: String
  @required
  likes: Integer
  @required
  date: Long
  quality: Integer
}
