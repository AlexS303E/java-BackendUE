package com.game.backend.runtimechanges.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuntimeChangesRepository extends JdbcRepository {
    public RuntimeChangesRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}