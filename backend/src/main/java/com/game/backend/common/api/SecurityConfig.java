package com.game.backend.common.api;

import com.game.backend.admin.application.AdminAuthenticationFilter;
import com.game.backend.auth.application.JwtAuthenticationFilter;
import com.game.backend.serverauth.application.ServerAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Общая настройка безопасности: публичные endpoints, player JWT, server identity и admin token фильтры.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    /**
     * Делает приложение stateless и подключает оба типа аутентификации до стандартного Spring фильтра.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtAuthenticationFilter,
        ServerAuthenticationFilter serverAuthenticationFilter,
        AdminAuthenticationFilter adminAuthenticationFilter
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/health",
                    "/actuator/health",
                    "/actuator/info",
                    "/auth/**",
                    "/catalog/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(adminAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(serverAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    /**
     * BCrypt используется для хранения паролей игроков.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
