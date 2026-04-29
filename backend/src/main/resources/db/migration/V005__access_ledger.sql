CREATE TABLE player_access_projection_state (
  player_id UUID PRIMARY KEY REFERENCES player_accounts(player_id),
  access_revision BIGINT NOT NULL,
  projection_rebuilt_at TIMESTAMPTZ NOT NULL,
  last_ledger_event_id UUID NULL
);

CREATE TABLE player_item_access (
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  item_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  is_hidden BOOLEAN NOT NULL,
  is_locked_in_shop BOOLEAN NOT NULL,
  is_locked_by_quest BOOLEAN NOT NULL,
  is_disabled BOOLEAN NOT NULL,
  disabled_reason TEXT NULL,
  unlock_hint_code TEXT NULL,
  unlock_hint_payload JSONB NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(player_id, item_id, catalog_version),
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);

CREATE TABLE entitlement_ledger (
  ledger_event_id UUID PRIMARY KEY,
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  item_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  event_type TEXT NOT NULL CHECK (event_type IN ('hide_item','reveal_item','shop_lock','shop_unlock','quest_lock','quest_unlock','item_disable','item_enable','compensation_unlock','admin_override')),
  source_type TEXT NOT NULL CHECK (source_type IN ('default','level','quest','shop','event','admin','compensation','system')),
  source_ref TEXT NULL,
  actor_type TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  correlation_id TEXT NULL,
  idempotency_key TEXT NOT NULL,
  payload JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL,
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version),
  UNIQUE(player_id, idempotency_key)
);
