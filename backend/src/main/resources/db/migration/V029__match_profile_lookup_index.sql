CREATE INDEX IF NOT EXISTS idx_match_profiles_fresh_dependency_lookup
  ON player_match_profiles(
    player_id,
    realm_id,
    class_tag,
    team_tag,
    weapon_preset_slot,
    outfit_preset_slot,
    catalog_version,
    weapon_preset_revision,
    outfit_preset_revision,
    access_revision,
    generated_at DESC
  )
  INCLUDE (payload, expires_at)
  WHERE is_stale = false;
