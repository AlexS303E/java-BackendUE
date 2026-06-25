export const PERFORMANCE_GATES = Object.freeze({
  health: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<100"],
  },
  catalog: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<100"],
  },
  auth: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
  access: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<200"],
  },
  presets: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<200"],
  },
  matchProfile: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<300"],
  },
  runtimeChanges: {
    http_req_failed: ["rate<1.00"],
    http_req_duration: ["p(95)<200"],
  },
  mixedSmoke: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
});
