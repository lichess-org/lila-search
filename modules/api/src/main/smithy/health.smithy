$version: "2"

namespace lila.search.spec

use alloy#simpleRestJson

@simpleRestJson
service HealthService {
  version: "3.0.0",
  operations: [HealthCheck]
}

@readonly
@http(method: "GET", uri: "/health", code: 200)
operation HealthCheck {
  output: HealthStatusOutput
}

enum ElasticStatus {
  Ok = "ok"
  Unreachable = "unreachable"
}

structure HealthStatusOutput {
  @required
  elastic: ElasticStatus
}
