package com.game.backend.common.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CommonRepository extends JdbcRepository {
    public CommonRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}