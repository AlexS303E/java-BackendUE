package com.game.backend.auth.application;

import com.game.backend.auth.api.RegisterRequest;
import com.game.backend.auth.api.RegisterResponse;
import com.game.backend.common.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AuthService {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PlayerBootstrapService playerBootstrapService;

    public AuthService(
        JdbcTemplate jdbcTemplate,
        PasswordEncoder passwordEncoder,
        PlayerBootstrapService playerBootstrapService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.playerBootstrapService = playerBootstrapService;
    }

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
}
