package com.game.backend.notifications.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationsRepository extends JdbcRepository {
    public NotificationsRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}