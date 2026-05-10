import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export const options = {
  vus: 25,
  duration: "3m",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<100"],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/actuator/health`);
  check(res, { "health ok": (r) => r.status === 200 });
}
