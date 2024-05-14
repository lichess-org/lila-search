$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson

@simpleRestJson
service HealthService {
  version: "3.0.0",
  operations: [HealthCheck]
}

@readonly
@http(method: "GET", uri: "/api/health", code: 200)
operation HealthCheck {
  output := {
    @required
    elastic: ElasticStatus
  }
}

enum ElasticStatus {
  red = "red"
  green = "green"
  yellow = "yellow"
}
