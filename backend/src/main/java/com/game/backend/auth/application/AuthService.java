package com.game.backend.auth.application;

import com.game.backend.auth.repository.AuthRepository;

import com.game.backend.common.api.ApiException;
import com.game.backend.auth.repository.AuthRepository.Account;
import com.game.backend.auth.repository.AuthRepository.RefreshSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PlayerBootstrapService playerBootstrapService;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final Duration refreshTokenTtl;
    private final String dummyPasswordHash;

    public AuthService(
        AuthRepository repository,
        PasswordEncoder passwordEncoder,
        PlayerBootstrapService playerBootstrapService,
        JwtTokenService jwtTokenService,
        RefreshTokenService refreshTokenService,
        @Value("${app.auth.refresh-token-ttl:P14D}") String refreshTokenTtl
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.playerBootstrapService = playerBootstrapService;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenTtl = Duration.parse(refreshTokenTtl);
        this.dummyPasswordHash = passwordEncoder.encode("unusable-login-password");
    }

    /**
     * Создает player_account и сразу bootstrap-ит базовые access/preset данные.
     */
    @Transactional
    public RegisteredPlayer register(String loginName, String password) {
        String normalizedLoginName = LoginNameNormalizer.normalize(loginName);
        if (normalizedLoginName.length() < 3 || normalizedLoginName.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOGIN_NAME", "Login name must contain 3 to 64 characters");
        }
        UUID playerId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String passwordHash = passwordEncoder.encode(password);

        try {
            repository.insertPlayerAccount(playerId, normalizedLoginName, passwordHash, now);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_NAME_ALREADY_EXISTS", "Login name is already taken");
        }

        playerBootstrapService.bootstrapNewPlayer(playerId, now);
        return new RegisteredPlayer(playerId, normalizedLoginName, "active", false);
    }

    /**
     * Проверяет пароль активного аккаунта и открывает новую refresh-сессию.
     */
    @Transactional
    public AuthTokenPair login(String loginName, String password) {
        Account account = accountByLoginName(LoginNameNormalizer.normalize(loginName));
        if (account == null) {
            passwordEncoder.matches(password, dummyPasswordHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid login name or password");
        }
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid login name or password");
        }
        ensureActive(account.status());
        return issueTokenPair(account.playerId(), account.loginName(), OffsetDateTime.now());
    }

    /**
     * Делает refresh rotation: старый refresh token отзывается, новая пара токенов сохраняется как новая сессия.
     */
    @Transactional
    public AuthTokenPair refresh(String refreshToken) {
        String refreshTokenHash = refreshTokenService.hashRefreshToken(refreshToken);
        List<RefreshSession> sessions = repository.lockActiveRefreshSessions(refreshTokenHash);
        if (sessions.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Refresh token is invalid");
        }

        RefreshSession session = sessions.getFirst();
        OffsetDateTime now = OffsetDateTime.now();
        if (!session.expiresAt().isAfter(now)) {
            repository.expireAuthSession(session.sessionId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Refresh token is expired");
        }

        ensureActive(session.accountStatus());
        repository.revokeAuthSession(session.sessionId(), now);
        return issueTokenPair(session.playerId(), session.loginName(), now);
    }

    /**
     * Идемпотентно отзывает refresh token, если он еще активен.
     */
    @Transactional
    public void logout(String refreshToken) {
        String refreshTokenHash = refreshTokenService.hashRefreshToken(refreshToken);
        repository.revokeActiveSessionByRefreshTokenHash(refreshTokenHash, OffsetDateTime.now());
    }

    private Account accountByLoginName(String loginName) {
        List<Account> accounts = repository.findAccountsByLoginName(loginName);
        return accounts.isEmpty() ? null : accounts.getFirst();
    }

    private void ensureActive(String status) {
        if (!"active".equals(status)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Account status does not allow authentication");
        }
    }

    /**
     * Создает access token и сохраняет только SHA-256 hash refresh token.
     */
    private AuthTokenPair issueTokenPair(UUID playerId, String loginName, OffsetDateTime now) {
        String accessToken = jwtTokenService.issueAccessToken(playerId, loginName);
        String refreshToken = refreshTokenService.generateRefreshToken();
        String refreshTokenHash = refreshTokenService.hashRefreshToken(refreshToken);
        OffsetDateTime refreshExpiresAt = now.plus(refreshTokenTtl);

        repository.insertAuthSession(
            UUID.randomUUID(),
            playerId,
            refreshTokenHash,
            now,
            refreshExpiresAt
        );

        return new AuthTokenPair(
            playerId,
            accessToken,
            "Bearer",
            jwtTokenService.accessTokenTtlSeconds(),
            refreshToken,
            refreshTokenTtl.toSeconds()
        );
    }
}
