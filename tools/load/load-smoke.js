import http from "k6/http";
import { check, group, sleep } from "k6";

const baseUrl = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const password = __ENV.LOAD_PASSWORD || "password123";
const userCount = Number(__ENV.LOAD_USERS || __ENV.K6_VUS || 5);
const realmId = __ENV.REALM_ID || "global";
const classTag = __ENV.CLASS_TAG || "class.assault";
const teamTag = __ENV.TEAM_TAG || "team.red";
const weaponPresetSlot = Number(__ENV.WEAPON_PRESET_SLOT || 1);
const outfitPresetSlot = Number(__ENV.OUTFIT_PRESET_SLOT || 1);
const weaponSlotId = __ENV.WEAPON_SLOT_ID || "primary";
const weaponId = __ENV.WEAPON_ID || "weapon.ak12";
const mountId = __ENV.MOUNT_ID || "weapon.ak12.mount.scope.01";
const moduleId = __ENV.MODULE_ID || "module.scope.red_dot_01";
const serverId = __ENV.SERVER_ID || "10000000-0000-0000-0000-000000000001";
const serverFingerprint = __ENV.SERVER_FINGERPRINT || "dev-ds-fingerprint";
const serverBuildId = __ENV.SERVER_BUILD_ID || "ds-dev-smoke";

export const options = {
  vus: Number(__ENV.K6_VUS || 5),
  duration: __ENV.K6_DURATION || "30s",
  thresholds: {
    checks: ["rate>0.95"],
    http_req_failed: ["rate<0.05"],
  },
};

const jsonHeaders = {
  "Content-Type": "application/json",
};

const serverHeaders = {
  "Content-Type": "application/json",
  "X-Server-Id": serverId,
  "X-Server-Certificate-Fingerprint": serverFingerprint,
};

export function setup() {
  const users = [];
  for (let index = 0; index < userCount; index += 1) {
    const loginName = `load_${Date.now()}_${index}_${randomHex(8)}`;
    const response = http.post(
      `${baseUrl}/auth/register`,
      JSON.stringify({
        login_name: loginName,
        password,
      }),
      { headers: jsonHeaders, tags: { endpoint: "setup_register" } }
    );

    if (response.status !== 201) {
      throw new Error(`Unable to register load user ${loginName}: ${response.status} ${response.body}`);
    }

    users.push({
      loginName,
      password,
      playerId: response.json("player_id"),
    });
  }
  return { users };
}

export default function (data) {
  const user = data.users[(__VU - 1) % data.users.length];
  let token;
  let playerId = user.playerId;
  let catalogVersion;
  let savedRevision;
  let matchId;

  group("player login", () => {
    const login = http.post(
      `${baseUrl}/auth/login`,
      JSON.stringify({
        login_name: user.loginName,
        password: user.password,
      }),
      { headers: jsonHeaders, tags: { endpoint: "POST /auth/login" } }
    );
    check(login, {
      "login ok": (response) => response.status === 200 && Boolean(response.json("access_token")),
    });
    token = login.json("access_token");
    playerId = login.json("player_id") || playerId;
  });

  const authHeaders = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };

  group("read catalog and player state", () => {
    const catalog = http.get(
      `${baseUrl}/catalog/snapshot?realm_id=${encodeURIComponent(realmId)}`,
      { tags: { endpoint: "GET /catalog/snapshot" } }
    );
    check(catalog, {
      "catalog snapshot ok": (response) => response.status === 200 && response.json("items").length > 0,
    });
    catalogVersion = Number(catalog.json("catalog_version"));

    const access = http.get(`${baseUrl}/me/access`, {
      headers: authHeaders,
      tags: { endpoint: "GET /me/access" },
    });
    check(access, {
      "access ok": (response) => response.status === 200 && response.json("items").length > 0,
    });

    const presets = http.get(`${baseUrl}/me/presets`, {
      headers: authHeaders,
      tags: { endpoint: "GET /me/presets" },
    });
    check(presets, {
      "presets ok": (response) => response.status === 200 && response.json("weapon_presets").length > 0,
    });

    const weaponPreset = findWeaponPreset(presets.json("weapon_presets"), classTag, weaponPresetSlot);
    catalogVersion = Number(weaponPreset.catalog_version || catalogVersion);

    const save = http.put(
      `${baseUrl}/me/presets/weapons/${encodeURIComponent(classTag)}/${weaponPresetSlot}`,
      JSON.stringify(weaponPresetSaveBody(catalogVersion)),
      {
        headers: {
          ...authHeaders,
          "If-Match": `"${weaponPreset.revision}"`,
        },
        tags: { endpoint: "PUT /me/presets/*" },
      }
    );
    check(save, {
      "save preset ok": (response) => response.status === 200 && Number(response.json("revision")) > Number(weaponPreset.revision),
    });
    savedRevision = Number(save.json("revision"));
  });

  group("server match profile and runtime change", () => {
    matchId = uuidv4();
    const profile = http.post(
      `${baseUrl}/server/match-profile/build`,
      JSON.stringify(matchProfileBuildBody(matchId, playerId, catalogVersion)),
      { headers: serverHeaders, tags: { endpoint: "POST /server/match-profile/build" } }
    );
    check(profile, {
      "match profile ok": (response) => response.status === 200 && response.json("player_id") === playerId,
    });

    const operationId = uuidv4();
    const runtimeChange = http.post(
      `${baseUrl}/server/runtime-preset-changes`,
      JSON.stringify(runtimePresetChangeBody(operationId, matchId, playerId, savedRevision)),
      {
        headers: {
          ...serverHeaders,
          "Idempotency-Key": operationId,
        },
        tags: { endpoint: "POST /server/runtime-preset-changes" },
      }
    );
    check(runtimeChange, {
      "runtime change ok": (response) => response.status === 200 && response.json("operation_id") === operationId,
    });
  });

  sleep(Number(__ENV.K6_SLEEP_SECONDS || 1));
}

function findWeaponPreset(weaponPresets, expectedClassTag, expectedSlot) {
  const preset = weaponPresets.find(
    (candidate) => candidate.class_tag === expectedClassTag && Number(candidate.preset_slot) === expectedSlot
  );
  if (!preset) {
    throw new Error(`Weapon preset ${expectedClassTag}/${expectedSlot} was not returned`);
  }
  return preset;
}

function weaponPresetSaveBody(version) {
  return {
    catalog_version: version,
    slots: [
      {
        weapon_slot_id: weaponSlotId,
        weapon_id: weaponId,
        modules: [
          {
            mount_id: mountId,
            module_id: moduleId,
          },
        ],
      },
      {
        weapon_slot_id: "grenade",
        weapon_id: null,
        modules: [],
      },
    ],
  };
}

function matchProfileBuildBody(matchId, playerId, version) {
  return {
    match_id: matchId,
    player_id: playerId,
    realm_id: realmId,
    class_tag: classTag,
    team_tag: teamTag,
    weapon_preset_slot: weaponPresetSlot,
    outfit_preset_slot: outfitPresetSlot,
    supported_catalog_versions: [version],
    preferred_catalog_version: version,
    server_build_id: serverBuildId,
  };
}

function runtimePresetChangeBody(operationId, matchId, playerId, baseRevision) {
  return {
    operation_id: operationId,
    operation_seq: 1,
    match_id: matchId,
    player_id: playerId,
    class_tag: classTag,
    weapon_preset_slot: weaponPresetSlot,
    base_weapon_preset_revision: baseRevision,
    runtime_change_payload: {
      schema_version: 1,
      changes: [
        {
          op: "set_module",
          weapon_slot_id: weaponSlotId,
          weapon_id: weaponId,
          mount_id: mountId,
          module_id: moduleId,
        },
      ],
    },
  };
}

function uuidv4() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (char) => {
    const value = Math.floor(Math.random() * 16);
    const nibble = char === "x" ? value : (value & 0x3) | 0x8;
    return nibble.toString(16);
  });
}

function randomHex(length) {
  let value = "";
  for (let index = 0; index < length; index += 1) {
    value += Math.floor(Math.random() * 16).toString(16);
  }
  return value;
}
