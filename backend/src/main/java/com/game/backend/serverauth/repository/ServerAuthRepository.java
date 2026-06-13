package com.game.backend.serverauth.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ServerAuthRepository extends JdbcRepository {
    public ServerAuthRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}