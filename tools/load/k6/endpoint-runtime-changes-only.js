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
  // 409 CONFLICT is expected after the first applied revision.
  thresholds: PERFORMANCE_GATES.runtimeChanges,
};

export function setup() {
  const accounts = [];
  for (let i = 0; i < options.vus; i++) {
    const loginName = `rtc_iso_${Date.now()}_${i}`;
    const reg = http.post(`${BASE_URL}/auth/register`, JSON.stringify({ login_name: loginName, password: PASSWORD }), {
      headers: { "Content-Type": "application/json" },
    });
    if (reg.status !== 201) continue;

    const playerId = reg.json("player_id");
    const matchId = cryptoRandomUUID();

    const buildBody = JSON.stringify({
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
    });
    const build = http.post(`${BASE_URL}/server/match-profile/build`, buildBody, {
      headers: {
        "Content-Type": "application/json",
        "X-Server-Id": SERVER_ID,
        "X-Server-Certificate-Fingerprint": SERVER_FINGERPRINT,
      },
    });
    if (build.status !== 200) continue;

    const weaponPresetRevision = build.json("dependency_revisions.weapon_preset_revision");
    accounts.push({ playerId, matchId, weaponPresetRevision });
  }
  return { accounts };
}

export default function (data) {
  if (data.accounts.length === 0) {
    check(null, { "no accounts": () => false });
    return;
  }

  const account = data.accounts[__VU % data.accounts.length];
  const operationId = cryptoRandomUUID();

  const body = JSON.stringify({
    operation_id: operationId,
    operation_seq: Date.now(),
    match_id: account.matchId,
    player_id: account.playerId,
    class_tag: "class.assault",
    weapon_preset_slot: 1,
    base_weapon_preset_revision: account.weaponPresetRevision,
    runtime_change_payload: {
      schema_version: 1,
      changes: [
        {
          op: "set_module",
          weapon_slot_id: "primary",
          weapon_id: "weapon.ak12",
          mount_id: "weapon.ak12.mount.scope.01",
          module_id: "module.scope.red_dot_01",
        },
      ],
    },
  });

  const r = http.post(`${BASE_URL}/server/runtime-preset-changes`, body, {
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": operationId,
      "X-Server-Id": SERVER_ID,
      "X-Server-Certificate-Fingerprint": SERVER_FINGERPRINT,
    },
  });

  check(r, {
    "runtime status is 200 or 409": (resp) => resp.status === 200 || resp.status === 409,
    "runtime no 5xx error": (resp) => resp.status < 500,
  });
}

function cryptoRandomUUID() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const v = Math.floor(Math.random() * 16);
    return (c === "x" ? v : (v & 0x3) | 0x8).toString(16);
  });
}
