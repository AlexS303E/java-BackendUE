package com.game.backend.auth.api;

import com.game.backend.auth.application.AuthTokenPair;
import com.game.backend.auth.application.AuthService;
import com.game.backend.auth.application.RegisteredPlayer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичный auth API для регистрации, входа, refresh rotation и logout.
 */
@RestController
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Создает игрока и стартовые проекции/presets.
     */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return toResponse(authService.register(request.loginName(), request.password()));
    }

    /**
     * Проверяет пароль и выдает пару access/refresh токенов.
     */
    @PostMapping("/auth/login")
    AuthTokenResponse login(@Valid @RequestBody LoginRequest request) {
        return toResponse(authService.login(request.loginName(), request.password()));
    }

    /**
     * Ротирует refresh token и выдает новую пару токенов.
     */
    @PostMapping("/auth/refresh")
    AuthTokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return toResponse(authService.refresh(request.refreshToken()));
    }

    /**
     * Отзывает активную refresh-сессию.
     */
    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private RegisterResponse toResponse(RegisteredPlayer player) {
        return new RegisterResponse(
            player.playerId(),
            player.loginName(),
            player.status(),
            player.needsBootstrap()
        );
    }

    private AuthTokenResponse toResponse(AuthTokenPair tokenPair) {
        return new AuthTokenResponse(
            tokenPair.playerId(),
            tokenPair.accessToken(),
            tokenPair.tokenType(),
            tokenPair.expiresIn(),
            tokenPair.refreshToken(),
            tokenPair.refreshExpiresIn()
        );
    }
}
