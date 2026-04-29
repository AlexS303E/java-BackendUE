CREATE TABLE realms (
  realm_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  is_active BOOLEAN NOT NULL
);

CREATE TABLE class_definitions (
  class_tag TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  is_active BOOLEAN NOT NULL
);

CREATE TABLE team_definitions (
  team_tag TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  is_active BOOLEAN NOT NULL
);

CREATE TABLE weapon_slot_definitions (
  weapon_slot_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  slot_group TEXT NULL,
  order_index INT NOT NULL,
  is_active BOOLEAN NOT NULL
);

CREATE TABLE clothing_slot_definitions (
  clothing_slot_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  order_index INT NOT NULL,
  is_active BOOLEAN NOT NULL
);

CREATE TABLE production_factories (
  factory_id TEXT PRIMARY KEY,
  factory_name TEXT NOT NULL,
  country_code TEXT NOT NULL,
  is_active BOOLEAN NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE(factory_name, country_code)
);
