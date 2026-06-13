package com.game.backend.access.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccessRepository extends JdbcRepository {
    public AccessRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}