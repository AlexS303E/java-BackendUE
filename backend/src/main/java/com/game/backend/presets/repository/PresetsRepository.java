package com.game.backend.presets.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PresetsRepository extends JdbcRepository {
    public PresetsRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}