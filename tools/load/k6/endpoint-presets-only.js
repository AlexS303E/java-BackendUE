import http from "k6/http";
import { check } from "k6";
import { PERFORMANCE_GATES } from "./performance-gates.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PASSWORD = "password123";

export const options = {
  vus: 25,
  duration: "3m",
  thresholds: PERFORMANCE_GATES.presets,
};

export function setup() {
  const tokens = [];
  for (let i = 0; i < options.vus; i++) {
    const loginName = `presets_iso_${Date.now()}_${i}`;
    const reg = http.post(`${BASE_URL}/auth/register`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (reg.status === 201) {
      const login = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
        headers: { "Content-Type": "application/json" },
      });
      if (login.status === 200) {
        tokens.push(login.json("access_token"));
      }
    }
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU % data.tokens.length];
  const res = http.get(`${BASE_URL}/me/presets`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  check(res, {
    "presets ok": (r) => r.status === 200 && r.json("weapon_presets").length > 0,
  });
}
