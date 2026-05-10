import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export const options = {
  vus: 25,
  duration: "3m",
  thresholds: {
    http_req_failed: ["rate<0.01"],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/catalog/snapshot?realm_id=global`);
  check(res, { "catalog ok": (r) => r.status === 200 && r.json("items").length > 0 });
}
