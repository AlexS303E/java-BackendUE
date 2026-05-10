import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PASSWORD = "password123";
const SERVER_ID = __ENV.SERVER_ID || "10000000-0000-0000-0000-000000000001";
const SERVER_FINGERPRINT = __ENV.SERVER_FINGERPRINT || "dev-ds-fingerprint";
const SERVER_BUILD_ID = __ENV.SERVER_BUILD_ID || "ds-dev-smoke";

export const options = {
  vus: 25,
  duration: "3m",
  thresholds: {
    http_req_failed: ["rate<0.05"],
  },
};

export function setup() {
  const accounts = [];
  for (let i = 0; i < options.vus; i++) {
    const loginName = `smoke_${Date.now()}_${i}`;
    const reg = http.post(`${BASE_URL}/auth/register`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (reg.status !== 201) continue;

    const login = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (login.status !== 200) continue;

    const playerId = login.json("player_id");
    const token = login.json("access_token");
    const matchId = uuidv4();

    const build = http.post(`${BASE_URL}/server/match-profile/build`, JSON.stringify({
      match_id: matchId,
      player_id: playerId,
      realm_id: "global",
      class_tag: "class.assault",
      team_tag: "team.red",
      weapon_preset_slot: 1,
      outfit_preset_slot: 1,
      supported_catalog_versions: [1],
      preferred_catalog_version: 1,
      server_build_id: SERVER_BUILD_ID,
    }), {
      headers: {
        "Content-Type": "application/json",
        "X-Server-Id": SERVER_ID,
        "X-Server-Certificate-Fingerprint": SERVER_FINGERPRINT,
      },
    });
    if (build.status !== 200) continue;

    const weaponPresetRevision = build.json("dependency_revisions.weapon_preset_revision");
    accounts.push({ playerId, token, matchId, weaponPresetRevision, loginName });
  }
  const catalog = http.get(`${BASE_URL}/catalog/snapshot?realm_id=global`);
  const catalogVersion = catalog.status === 200 ? Number(catalog.json("catalog_version")) : 1;
  return { accounts, catalogVersion };
}

export default function (data) {
  if (data.accounts.length === 0) {
    check(null, { "no accounts": () => false });
    return;
  }

  const account = data.accounts[__VU % data.accounts.length];
  const headers = { "Content-Type": "application/json", "Authorization": `Bearer ${account.token}` };
  const serverHeaders = {
    "Content-Type": "application/json",
    "X-Server-Id": SERVER_ID,
    "X-Server-Certificate-Fingerprint": SERVER_FINGERPRINT,
  };
  const vuIdx = __VU;
  const iter = __ITER;

  // Rotate through endpoint types
  const endpoint = (iter + vuIdx) % 7;

  switch (endpoint) {
    case 0: {
      // GET /actuator/health
      const r = http.get(`${BASE_URL}/actuator/health`);
      check(r, { "health status 200": (resp) => resp.status === 200 });
      break;
    }
    case 1: {
      // GET /catalog/snapshot
      const r = http.get(`${BASE_URL}/catalog/snapshot?realm_id=global`);
      check(r, { "catalog status 200": (resp) => resp.status === 200 });
      break;
    }
    case 2: {
      // GET /me/access
      const r = http.get(`${BASE_URL}/me/access`, { headers });
      check(r, { "access status 200": (resp) => resp.status === 200 });
      break;
    }
    case 3: {
      // GET /me/presets
      const r = http.get(`${BASE_URL}/me/presets`, { headers });
      check(r, { "presets status 200": (resp) => resp.status === 200 });
      break;
    }
    case 4: {
      // POST /server/match-profile/build
      const newMatchId = uuidv4();
      const r = http.post(`${BASE_URL}/server/match-profile/build`, JSON.stringify({
        match_id: newMatchId,
        player_id: account.playerId,
        realm_id: "global",
        class_tag: "class.assault",
        team_tag: "team.red",
        weapon_preset_slot: 1,
        outfit_preset_slot: 1,
        supported_catalog_versions: [1],
        preferred_catalog_version: 1,
        server_build_id: SERVER_BUILD_ID,
      }), { headers: serverHeaders });
      check(r, { "build status 200 or 422": (resp) => resp.status === 200 || resp.status === 422 });
      break;
    }
    case 5: {
      // POST /auth/login
      const r = http.post(`${BASE_URL}/auth/login`, JSON.stringify({
        login_name: account.loginName,
        password: PASSWORD,
      }), { headers: { "Content-Type": "application/json" } });
      check(r, { "login status 200": (resp) => resp.status === 200 });
      break;
    }
    case 6: {
      // POST /server/runtime-preset-changes
      const opId = uuidv4();
      const r = http.post(`${BASE_URL}/server/runtime-preset-changes`, JSON.stringify({
        operation_id: opId,
        operation_seq: Date.now() + iter,
        match_id: account.matchId,
        player_id: account.playerId,
        class_tag: "class.assault",
        weapon_preset_slot: 1,
        base_weapon_preset_revision: account.weaponPresetRevision,
        runtime_change_payload: {
          schema_version: 1,
          changes: [{ op: "set_module", weapon_slot_id: "primary", weapon_id: "weapon.ak12", mount_id: "weapon.ak12.mount.scope.01", module_id: "module.scope.red_dot_01" }],
        },
      }), { headers: { ...serverHeaders, "Idempotency-Key": opId } });
      check(r, { "runtime status 200 or 409": (resp) => resp.status === 200 || resp.status === 409 });
      break;
    }
  }

  sleep(0.1);
}

function uuidv4() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const v = Math.floor(Math.random() * 16);
    return (c === "x" ? v : (v & 0x3) | 0x8).toString(16);
  });
}
