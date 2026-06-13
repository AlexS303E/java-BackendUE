package com.game.backend.postmatch.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostMatchRepository extends JdbcRepository {
    public PostMatchRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}