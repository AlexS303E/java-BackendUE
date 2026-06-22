# DDL Decisions

The Flyway migrations initially follow the v13 Canonical DB schema.

Stage 1 additions:

- `V029__match_profile_lookup_index.sql` adds
  `idx_match_profiles_fresh_dependency_lookup` for fresh match profile cache
  lookup by the full dependency tuple: player, realm, class, team, preset slots,
  catalog version, weapon preset revision, outfit preset revision, and access
  revision. The index includes `payload` and `expires_at` to keep the hot
  `POST /server/match-profile/build` cache-hit path bounded.

Open issue before production freeze:

- Weapon/outfit preset primary keys in v13 canonical schema omit `catalog_version`, while the prose says `catalog_version` belongs to the preset container key.
