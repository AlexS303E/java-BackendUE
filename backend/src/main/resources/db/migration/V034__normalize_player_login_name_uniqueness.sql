CREATE UNIQUE INDEX uq_player_accounts_login_name_normalized
    ON player_accounts (lower(btrim(login_name)));
