package com.game.backend.auth.application;

import com.game.backend.auth.repository.AuthRepository;

import com.game.backend.auth.api.AuthTokenResponse;
import com.game.backend.auth.api.LoginRequest;
import com.game.backend.auth.api.LogoutRequest;
import com.game.backend.auth.api.RefreshRequest;
import com.game.backend.auth.api.RegisterRequest;
import com.game.backend.auth.api.RegisterResponse;
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
            repository.insertPlayerAccount(playerId, request.loginName(), passwordHash, now);
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
    public void logout(LogoutRequest request) {
        String refreshTokenHash = refreshTokenService.hashRefreshToken(request.refreshToken());
        repository.revokeActiveSessionByRefreshTokenHash(refreshTokenHash, OffsetDateTime.now());
    }

    private Account accountByLoginName(String loginName) {
        List<Account> accounts = repository.findAccountsByLoginName(loginName);
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

        repository.insertAuthSession(
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
}
