package com.game.backend.catalog.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository extends JdbcRepository {
    public CatalogRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}