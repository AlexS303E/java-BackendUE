package com.game.backend.auth.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository extends JdbcRepository {
    public AuthRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}