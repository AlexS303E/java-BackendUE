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
  const catalog = http.get(`${BASE_URL}/catalog/snapshot?realm_id=global`);
  const catalogVersion = catalog.status === 200 ? Number(catalog.json("catalog_version")) : 1;
  const accounts = [];
  for (let i = 0; i < options.vus; i++) {
    const loginName = `warm_${Date.now()}_${i}`;
    const reg = http.post(`${BASE_URL}/auth/register`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (reg.status !== 201) continue;

    const login = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (login.status !== 200) continue;

    const playerId = login.json("player_id");

    // Pre-build profile in setup to ensure warm reuse path
    const matchId = uuidv4();
    const build = http.post(`${BASE_URL}/server/match-profile/build`, JSON.stringify({
      match_id: matchId,
      player_id: playerId,
      realm_id: "global",
      class_tag: "class.assault",
      team_tag: "team.red",
      weapon_preset_slot: 1,
      outfit_preset_slot: 1,
      supported_catalog_versions: [catalogVersion],
      preferred_catalog_version: catalogVersion,
      server_build_id: SERVER_BUILD_ID,
      game_mode_id: "default",
    }), {
      headers: {
        "Content-Type": "application/json",
        "X-Server-Id": SERVER_ID,
        "X-Server-Certificate-Fingerprint": SERVER_FINGERPRINT,
      },
    });

    accounts.push({
      playerId,
      matchId: build.status === 200 ? matchId : null,
      token: login.json("access_token"),
    });
  }
  return { accounts, catalogVersion };
}

export default function (data) {
  const account = data.accounts[__VU % data.accounts.length];
  // Reuse the same match_id from setup → findExistingProfile returns cached profile
  const r = http.post(`${BASE_URL}/server/match-profile/build`, JSON.stringify({
    match_id: account.matchId || uuidv4(),
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
  }), {
    headers: {
      "Content-Type": "application/json",
      "X-Server-Id": SERVER_ID,
      "X-Server-Certificate-Fingerprint": SERVER_FINGERPRINT,
    },
  });

  check(r, { "warm build status 200": (resp) => resp.status === 200 });
}

function uuidv4() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const v = Math.floor(Math.random() * 16);
    return (c === "x" ? v : (v & 0x3) | 0x8).toString(16);
  });
}
