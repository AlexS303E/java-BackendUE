import http from "k6/http";
import { check } from "k6";
import { PERFORMANCE_GATES } from "./performance-gates.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PASSWORD = "password123";
const SERVER_ID = __ENV.SERVER_ID || "10000000-0000-0000-0000-000000000001";
const SERVER_FINGERPRINT = __ENV.SERVER_FINGERPRINT || "dev-ds-fingerprint";
const SERVER_BUILD_ID = __ENV.SERVER_BUILD_ID || "ds-dev-smoke";

export const options = {
  vus: 25,
  duration: "3m",
  thresholds: PERFORMANCE_GATES.matchProfile,
};

export function setup() {
  const accounts = [];
  for (let i = 0; i < options.vus; i++) {
    const loginName = `match_iso_${Date.now()}_${i}`;
    const reg = http.post(`${BASE_URL}/auth/register`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (reg.status === 201) {
      const login = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
        headers: { "Content-Type": "application/json" },
      });
      if (login.status === 200) {
        accounts.push({ playerId: login.json("player_id"), token: login.json("access_token") });
      }
    }
  }
  const catalog = http.get(`${BASE_URL}/catalog/snapshot?realm_id=global`);
  const catalogVersion = catalog.status === 200 ? Number(catalog.json("catalog_version")) : 1;
  return { accounts, catalogVersion };
}

export default function (data) {
  const account = data.accounts[__VU % data.accounts.length];
  const matchId = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const v = Math.floor(Math.random() * 16);
    return (c === "x" ? v : (v & 0x3) | 0x8).toString(16);
  });
  const body = JSON.stringify({
    match_id: matchId,
    player_id: account.playerId,
    realm_id: "global",
    class_tag: "class.assault",
    team_tag: "team.red",
    weapon_preset_slot: 1,
    outfit_preset_slot: 1,
    supported_catalog_versions: [data.catalogVersion],
    preferred_catalog_version: data.catalogVersion,
    server_build_id: SERVER_BUILD_ID,
    game_mode_id: "default",
  });
  const res = http.post(`${BASE_URL}/server/match-profile/build`, body, {
    headers: {
      "Content-Type": "application/json",
      "X-Server-Id": SERVER_ID,
      "X-Server-Certificate-Fingerprint": SERVER_FINGERPRINT,
    },
  });
  check(res, {
    "match profile ok": (r) => r.status === 200 || r.status === 422,
  });
}
