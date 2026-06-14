package com.game.backend.auth.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class AuthRepository extends JdbcRepository {
    public record Account(
        UUID playerId,
        String loginName,
        String passwordHash,
        String status
    ) {
    }

    public record RefreshSession(
        UUID sessionId,
        UUID playerId,
        String loginName,
        String accountStatus,
        OffsetDateTime expiresAt
    ) {
    }

    public AuthRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public void insertPlayerAccount(UUID playerId, String loginName, String passwordHash, OffsetDateTime now) {
        update(
            """
                INSERT INTO player_accounts(
                  player_id,
                  login_name,
                  password_hash,
                  status,
                  created_at,
                  updated_at
                )
                VALUES (?, ?, ?, 'active', ?, ?)
                """,
            playerId,
            loginName,
            passwordHash,
            now,
            now
        );
    }

    public List<Account> findAccountsByLoginName(String loginName) {
        return query(
            """
                SELECT player_id, login_name, password_hash, status
                FROM player_accounts
                WHERE login_name = ?
                """,
            (rs, rowNum) -> new Account(
                rs.getObject("player_id", UUID.class),
                rs.getString("login_name"),
                rs.getString("password_hash"),
                rs.getString("status")
            ),
            loginName
        );
    }

    public List<RefreshSession> lockActiveRefreshSessions(String refreshTokenHash) {
        return query(
            """
                SELECT
                  pas.session_id,
                  pas.player_id,
                  pa.login_name,
                  pa.status AS account_status,
                  pas.expires_at
                FROM player_auth_sessions pas
                JOIN player_accounts pa ON pa.player_id = pas.player_id
                WHERE pas.refresh_token_hash = ?
                  AND pas.status = 'active'
                FOR UPDATE OF pas
                """,
            (rs, rowNum) -> new RefreshSession(
                rs.getObject("session_id", UUID.class),
                rs.getObject("player_id", UUID.class),
                rs.getString("login_name"),
                rs.getString("account_status"),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            refreshTokenHash
        );
    }

    public void expireAuthSession(UUID sessionId) {
        update(
            """
                UPDATE player_auth_sessions
                SET status = 'expired'
                WHERE session_id = ?
                """,
            sessionId
        );
    }

    public void revokeAuthSession(UUID sessionId, OffsetDateTime now) {
        update(
            """
                UPDATE player_auth_sessions
                SET status = 'revoked',
                    revoked_at = ?
                WHERE session_id = ?
                """,
            now,
            sessionId
        );
    }

    public void revokeActiveSessionByRefreshTokenHash(String refreshTokenHash, OffsetDateTime now) {
        update(
            """
                UPDATE player_auth_sessions
                SET status = 'revoked',
                    revoked_at = ?
                WHERE refresh_token_hash = ?
                  AND status = 'active'
                """,
            now,
            refreshTokenHash
        );
    }

    public void insertAuthSession(
        UUID sessionId,
        UUID playerId,
        String refreshTokenHash,
        OffsetDateTime now,
        OffsetDateTime expiresAt
    ) {
        update(
            """
                INSERT INTO player_auth_sessions(
                  session_id,
                  player_id,
                  refresh_token_hash,
                  status,
                  created_at,
                  expires_at
                )
                VALUES (?, ?, ?, 'active', ?, ?)
                """,
            sessionId,
            playerId,
            refreshTokenHash,
            now,
            expiresAt
        );
    }
}
