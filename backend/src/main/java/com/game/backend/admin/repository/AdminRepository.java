package com.game.backend.admin.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRepository extends JdbcRepository {
    public AdminRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}