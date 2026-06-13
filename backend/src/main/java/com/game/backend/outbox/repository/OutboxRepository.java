package com.game.backend.outbox.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository extends JdbcRepository {
    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}