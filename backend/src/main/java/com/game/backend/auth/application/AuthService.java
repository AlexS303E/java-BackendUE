package com.game.backend.auth.application;

import com.game.backend.auth.api.AuthTokenResponse;
import com.game.backend.auth.api.LoginRequest;
import com.game.backend.auth.api.LogoutRequest;
import com.game.backend.auth.api.RefreshRequest;
import com.game.backend.auth.api.RegisterRequest;
import com.game.backend.auth.api.RegisterResponse;
import com.game.backend.common.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Оркестрирует регистрацию игрока, выдачу JWT и lifecycle refresh-сессий.
 */
@Service
public class AuthService {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PlayerBootstrapService playerBootstrapService;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final Duration refreshTokenTtl;

    public AuthService(
        JdbcTemplate jdbcTemplate,
        PasswordEncoder passwordEncoder,
        PlayerBootstrapService playerBootstrapService,
        JwtTokenService jwtTokenService,
        RefreshTokenService refreshTokenService,
        @Value("${app.auth.refresh-token-ttl:P14D}") String refreshTokenTtl
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.playerBootstrapService = playerBootstrapService;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenTtl = Duration.parse(refreshTokenTtl);
    }

    /**
     * Создает player_account и сразу bootstrap-ит базовые access/preset данные.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        UUID playerId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String passwordHash = passwordEncoder.encode(request.password());

        try {
            jdbcTemplate.update(
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
                request.loginName(),
                passwordHash,
                now,
                now
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_NAME_ALREADY_EXISTS", "Login name is already taken");
        }

        playerBootstrapService.bootstrapNewPlayer(playerId, now);
        return new RegisterResponse(playerId, request.loginName(), "active", false);
    }

    /**
     * Проверяет пароль активного аккаунта и открывает новую refresh-сессию.
     */
    @Transactional
    public AuthTokenResponse login(LoginRequest request) {
        Account account = accountByLoginName(request.loginName());
        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid login name or password");
        }
        ensureActive(account.status());
        return issueTokenPair(account.playerId(), account.loginName(), OffsetDateTime.now());
    }

    /**
     * Делает refresh rotation: старый refresh token отзывается, новая пара токенов сохраняется как новая сессия.
     */
    @Transactional
    public AuthTokenResponse refresh(RefreshRequest request) {
        String refreshTokenHash = refreshTokenService.hashRefreshToken(request.refreshToken());
        List<RefreshSession> sessions = jdbcTemplate.query(
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
        if (sessions.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Refresh token is invalid");
        }

        RefreshSession session = sessions.getFirst();
        OffsetDateTime now = OffsetDateTime.now();
        if (!session.expiresAt().isAfter(now)) {
            jdbcTemplate.update(
                "UPDATE player_auth_sessions SET status = 'expired' WHERE session_id = ?",
                session.sessionId()
            );
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Refresh token is expired");
        }

        ensureActive(session.accountStatus());
        jdbcTemplate.update(
            "UPDATE player_auth_sessions SET status = 'revoked', revoked_at = ? WHERE session_id = ?",
            now,
            session.sessionId()
        );
        return issueTokenPair(session.playerId(), session.loginName(), now);
    }

    /**
     * Идемпотентно отзывает refresh token, если он еще активен.
     */
    @Transactional
    public void logout(LogoutRequest request) {
        String refreshTokenHash = refreshTokenService.hashRefreshToken(request.refreshToken());
        jdbcTemplate.update(
            """
                UPDATE player_auth_sessions
                SET status = 'revoked',
                    revoked_at = ?
                WHERE refresh_token_hash = ?
                  AND status = 'active'
                """,
            OffsetDateTime.now(),
            refreshTokenHash
        );
    }

    private Account accountByLoginName(String loginName) {
        List<Account> accounts = jdbcTemplate.query(
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
        if (accounts.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid login name or password");
        }
        return accounts.getFirst();
    }

    private void ensureActive(String status) {
        if (!"active".equals(status)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Account status does not allow authentication");
        }
    }

    /**
     * Создает access token и сохраняет только SHA-256 hash refresh token.
     */
    private AuthTokenResponse issueTokenPair(UUID playerId, String loginName, OffsetDateTime now) {
        String accessToken = jwtTokenService.issueAccessToken(playerId, loginName);
        String refreshToken = refreshTokenService.generateRefreshToken();
        String refreshTokenHash = refreshTokenService.hashRefreshToken(refreshToken);
        OffsetDateTime refreshExpiresAt = now.plus(refreshTokenTtl);

        jdbcTemplate.update(
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
            UUID.randomUUID(),
            playerId,
            refreshTokenHash,
            now,
            refreshExpiresAt
        );

        return new AuthTokenResponse(
            playerId,
            accessToken,
            "Bearer",
            jwtTokenService.accessTokenTtlSeconds(),
            refreshToken,
            refreshTokenTtl.toSeconds()
        );
    }

    private record Account(
        UUID playerId,
        String loginName,
        String passwordHash,
        String status
    ) {
    }

    private record RefreshSession(
        UUID sessionId,
        UUID playerId,
        String loginName,
        String accountStatus,
        OffsetDateTime expiresAt
    ) {
    }
}
