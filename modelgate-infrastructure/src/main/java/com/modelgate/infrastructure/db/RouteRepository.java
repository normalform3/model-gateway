package com.modelgate.infrastructure.db;

import com.modelgate.common.domain.RouteTarget;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class RouteRepository {
    private final JdbcTemplate jdbcTemplate;

    public RouteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RouteTarget> findFirstTarget(String logicalModel) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                            SELECT d.id deployment_id, r.logical_model, p.name provider, d.actual_model
                            FROM model_route r
                            JOIN route_target rt ON rt.route_id = r.id AND rt.enabled = 1
                            JOIN model_deployment d ON d.id = rt.deployment_id AND d.enabled = 1
                            JOIN provider p ON p.id = d.provider_id AND p.enabled = 1
                            WHERE r.logical_model = ? AND r.enabled = 1
                            ORDER BY rt.weight DESC, rt.id ASC
                            LIMIT 1
                            """,
                    (rs, rowNum) -> new RouteTarget(
                            rs.getLong("deployment_id"),
                            rs.getString("logical_model"),
                            rs.getString("provider"),
                            rs.getString("actual_model")),
                    logicalModel));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
