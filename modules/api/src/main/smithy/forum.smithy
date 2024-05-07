$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson

@simpleRestJson
service SearchService {
  version: "3.0.0",
  operations: [SearchForum]
}

@readonly
@http(method: "POST", uri: "/search/forum/{from}/{size}", code: 200)
operation SearchForum {
  input: SearchForumInput
  output: SearchResponse
  errors: [InternalServerError]
}

@readonly
@http(method: "POST", uri: "/count/forum", code: 200)
operation CountForum {
  input: ForumInputBody
  output: CountResponse
  errors: [InternalServerError]
}

structure SearchForumInput {

  @required
  body: ForumInputBody

  @required
  @httpLabel
  from: Integer

  @required
  @httpLabel
  size: Integer
}

structure ForumInputBody {
  troll: Boolean
  query: String
}
