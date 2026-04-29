CREATE TABLE catalog_versions (
  catalog_version BIGINT PRIMARY KEY,
  artifact_hash TEXT NOT NULL,
  schema_version INT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('draft','validated','canary','active','previous','rolled_back','retired')),
  created_at TIMESTAMPTZ NOT NULL,
  validated_at TIMESTAMPTZ NULL,
  activated_at TIMESTAMPTZ NULL,
  retired_at TIMESTAMPTZ NULL
);

CREATE TABLE catalog_deployments (
  realm_id TEXT NOT NULL REFERENCES realms(realm_id),
  catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  deployment_state TEXT NOT NULL CHECK (deployment_state IN ('canary','active','previous','retired','rolled_back')),
  rollout_percent INT NOT NULL CHECK (rollout_percent BETWEEN 0 AND 100),
  allow_new_matches BOOLEAN NOT NULL,
  allow_existing_matches BOOLEAN NOT NULL,
  activated_at TIMESTAMPTZ NULL,
  retired_at TIMESTAMPTZ NULL,
  PRIMARY KEY(realm_id, catalog_version)
);

CREATE UNIQUE INDEX uq_catalog_active_new_matches
  ON catalog_deployments(realm_id)
  WHERE deployment_state = 'active' AND allow_new_matches = true;

CREATE TABLE catalog_id_migration_map (
  from_catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  to_catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  id_type TEXT NOT NULL CHECK (id_type IN ('item','mount','slot')),
  old_id TEXT NOT NULL,
  new_id TEXT NULL,
  migration_action TEXT NOT NULL CHECK (migration_action IN ('map','drop','manual')),
  PRIMARY KEY(from_catalog_version, to_catalog_version, id_type, old_id),
  CHECK ((migration_action = 'map' AND new_id IS NOT NULL) OR (migration_action IN ('drop','manual')))
);

CREATE TABLE catalog_items (
  item_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  item_type TEXT NOT NULL CHECK (item_type IN ('weapon','module','clothing','skin','consumable')),
  display_name TEXT NOT NULL,
  factory_id TEXT NULL REFERENCES production_factories(factory_id),
  country_code TEXT NULL,
  is_enabled BOOLEAN NOT NULL,
  payload_schema_version INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(item_id, catalog_version)
);

CREATE TABLE catalog_item_fragments (
  item_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  fragment_type TEXT NOT NULL,
  fragment_order INT NOT NULL,
  payload JSONB NOT NULL,
  payload_schema_version INT NOT NULL,
  PRIMARY KEY(item_id, catalog_version, fragment_type, fragment_order),
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);
