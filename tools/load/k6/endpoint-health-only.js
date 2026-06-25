import http from "k6/http";
import { check } from "k6";
import { PERFORMANCE_GATES } from "./performance-gates.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export const options = {
  vus: 25,
  duration: "3m",
  thresholds: PERFORMANCE_GATES.health,
};

export default function () {
  const res = http.get(`${BASE_URL}/actuator/health`);
  check(res, { "health ok": (r) => r.status === 200 });
}
