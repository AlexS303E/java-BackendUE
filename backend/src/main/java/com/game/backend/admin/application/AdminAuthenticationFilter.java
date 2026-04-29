package com.game.backend.admin.application;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Закрывает /admin/* простым dev-token до появления полноценного IAM/admin RBAC.
 */
@Component
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    private static final String ADMIN_ID_HEADER = "X-Admin-Id";
    private static final String DEFAULT_ADMIN_ID = "dev-admin";

    private final String adminToken;

    public AdminAuthenticationFilter(@Value("${app.admin.token}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    /**
     * Проверяет служебный токен и кладет AdminIdentity в SecurityContext для контроллеров /admin/*.
     */
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = request.getHeader(ADMIN_TOKEN_HEADER);
        if (adminToken == null || adminToken.isBlank() || token == null || !adminToken.equals(token)) {
            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Admin token is required");
            return;
        }

        String actorId = request.getHeader(ADMIN_ID_HEADER);
        if (actorId == null || actorId.isBlank()) {
            actorId = DEFAULT_ADMIN_ID;
        }

        AdminIdentity identity = new AdminIdentity(actorId.trim());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            identity,
            "admin",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void writeProblem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write(
            "{\"title\":\"" + code + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}"
        );
    }
}
