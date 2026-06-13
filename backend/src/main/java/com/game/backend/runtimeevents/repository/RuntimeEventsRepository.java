package com.game.backend.runtimeevents.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuntimeEventsRepository extends JdbcRepository {
    public RuntimeEventsRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}