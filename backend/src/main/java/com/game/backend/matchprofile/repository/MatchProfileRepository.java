package com.game.backend.matchprofile.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchProfileRepository extends JdbcRepository {
    public MatchProfileRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}