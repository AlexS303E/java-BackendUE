package com.game.backend.common.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

public abstract class JdbcRepository {
    private final JdbcTemplate jdbcTemplate;

    protected JdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int update(String sql, Object... args) {
        return jdbcTemplate.update(sql, args);
    }

    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        return jdbcTemplate.query(sql, rowMapper, args);
    }

    public <T> T query(String sql, ResultSetExtractor<T> resultSetExtractor, Object... args) {
        return jdbcTemplate.query(sql, resultSetExtractor, args);
    }

    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return jdbcTemplate.queryForObject(sql, requiredType, args);
    }

    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
        return jdbcTemplate.queryForList(sql, elementType, args);
    }

    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }
}

