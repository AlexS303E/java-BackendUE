import http from "k6/http";
import { check } from "k6";
import { PERFORMANCE_GATES } from "./performance-gates.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PASSWORD = "password123";

export const options = {
  vus: 25,
  duration: "3m",
  thresholds: PERFORMANCE_GATES.auth,
};

export function setup() {
  const users = [];
  for (let i = 0; i < options.vus; i++) {
    const loginName = `auth_iso_${Date.now()}_${i}`;
    const r = http.post(`${BASE_URL}/auth/register`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (r.status === 201) {
      users.push({ loginName, password: PASSWORD });
    }
  }
  return { users };
}

export default function (data) {
  const user = data.users[__VU % data.users.length];
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ login_name: user.loginName, password: user.password }),
    { headers: { "Content-Type": "application/json" } }
  );
  check(res, {
    "login ok": (r) => r.status === 200 && Boolean(r.json("access_token")),
  });
}
