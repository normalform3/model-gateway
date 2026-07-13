package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.ApplicationItem;
import com.modelgate.common.api.AdminDtos.ApplicationListResponse;
import com.modelgate.common.api.AdminDtos.CreateApplicationRequest;
import com.modelgate.common.api.AdminDtos.DeploymentItem;
import com.modelgate.common.api.AdminDtos.DeploymentListResponse;
import com.modelgate.common.api.AdminDtos.DashboardOverview;
import com.modelgate.common.api.AdminDtos.LogicalModelItem;
import com.modelgate.common.api.AdminDtos.LogicalModelListResponse;
import com.modelgate.common.api.AdminDtos.ProviderListResponse;
import com.modelgate.common.api.AdminDtos.ProviderSummary;
import com.modelgate.common.api.AdminDtos.RouteTargetItem;
import com.modelgate.common.api.AdminDtos.TeamModelAccessResponse;
import com.modelgate.common.api.AdminDtos.UpdateGlobalRuntimePolicyRequest;
import com.modelgate.common.api.AdminDtos.UpsertDeploymentRequest;
import com.modelgate.common.api.AdminDtos.UpsertLogicalModelRequest;
import com.modelgate.common.api.AdminDtos.UpsertProviderRequest;
import com.modelgate.common.api.AdminDtos.UpdateProviderRequest;
import com.modelgate.common.api.AdminDtos.UpsertRouteTargetRequest;
import com.modelgate.common.api.AdminDtos.VirtualApiKeyItem;
import com.modelgate.common.api.AdminDtos.VirtualApiKeyListResponse;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.common.domain.GlobalRuntimePolicy;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class AdminControlRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminControlRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ProviderListResponse listProviders(String keyword, String providerType, Boolean enabled, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) { where.append(" AND p.name LIKE ?"); args.add("%" + keyword.trim() + "%"); }
        if (providerType != null && !providerType.isBlank()) { where.append(" AND p.provider_type = ?"); args.add(providerType); }
        if (enabled != null) { where.append(" AND p.enabled = ?"); args.add(bool(enabled, true)); }
        long total = longCount("SELECT COUNT(*) FROM provider p" + where, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<ProviderSummary> items = jdbcTemplate.query("""
                        SELECT p.id, p.name, p.provider_type, p.base_url, p.enabled, p.created_at
                        FROM provider p
                        """ + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?", (rs, rowNum) -> new ProviderSummary(
                rs.getLong("id"), rs.getString("name"), rs.getString("provider_type"), rs.getString("base_url"),
                rs.getInt("enabled") == 1, JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), pageArgs.toArray());
        return new ProviderListResponse(items, page, size, total);
    }

    public ProviderSummary createProvider(UpsertProviderRequest request) {
        long id = GeneratedKeys.insert(jdbcTemplate, """
                        INSERT INTO provider(name, provider_type, base_url, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, request.name(), providerType(request.providerType()), blankToNull(request.baseUrl()), bool(request.enabled(), true), now());
        return findProvider(id);
    }

    public ProviderSummary updateProvider(long providerId, UpdateProviderRequest request) {
        jdbcTemplate.update("""
                        UPDATE provider SET name = COALESCE(NULLIF(?, ''), name), provider_type = COALESCE(NULLIF(?, ''), provider_type),
                          base_url = COALESCE(NULLIF(?, ''), base_url), enabled = COALESCE(?, enabled)
                        WHERE id = ?
                        """, request.name(), providerTypeOrNull(request.providerType()), request.baseUrl(), request.enabled() == null ? null : bool(request.enabled(), true), providerId);
        return findProvider(providerId);
    }

    @Transactional
    public void deleteProvider(long providerId) {
        requireProvider(providerId);
        List<Long> deploymentIds = jdbcTemplate.queryForList(
                "SELECT id FROM model_deployment WHERE provider_id = ?", Long.class, providerId);
        for (Long deploymentId : deploymentIds) {
            jdbcTemplate.update("DELETE FROM route_target WHERE deployment_id = ?", deploymentId);
        }
        jdbcTemplate.update("DELETE FROM provider_model WHERE provider_id = ?", providerId);
        jdbcTemplate.update("DELETE FROM provider_credential WHERE provider_id = ?", providerId);
        jdbcTemplate.update("DELETE FROM model_deployment WHERE provider_id = ?", providerId);
        jdbcTemplate.update("DELETE FROM provider WHERE id = ?", providerId);
    }

    public DeploymentListResponse listDeployments(long providerId) {
        requireProvider(providerId);
        return new DeploymentListResponse(jdbcTemplate.query("""
                        SELECT id, provider_id, name, actual_model, enabled, input_price_per_million,
                               output_price_per_million, currency FROM model_deployment WHERE provider_id = ? ORDER BY id DESC
                        """, (rs, rowNum) -> new DeploymentItem(rs.getLong("id"), rs.getLong("provider_id"), rs.getString("name"),
                rs.getString("actual_model"), rs.getInt("enabled") == 1, rs.getBigDecimal("input_price_per_million"),
                rs.getBigDecimal("output_price_per_million"), rs.getString("currency")), providerId));
    }

    public DeploymentItem createDeployment(long providerId, UpsertDeploymentRequest request) {
        requireProvider(providerId);
        long id = GeneratedKeys.insert(jdbcTemplate, """
                        INSERT INTO model_deployment(provider_id, name, actual_model, enabled, input_price_per_million, output_price_per_million, currency, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, providerId, request.name(), request.actualModel(), bool(request.enabled(), true), price(request.inputPricePerMillion()),
                price(request.outputPricePerMillion()), currency(request.currency()), now());
        return findDeployment(id);
    }

    public DeploymentItem updateDeployment(long deploymentId, UpsertDeploymentRequest request) {
        jdbcTemplate.update("""
                        UPDATE model_deployment SET name = COALESCE(NULLIF(?, ''), name), actual_model = COALESCE(NULLIF(?, ''), actual_model),
                          enabled = COALESCE(?, enabled), input_price_per_million = COALESCE(?, input_price_per_million),
                          output_price_per_million = COALESCE(?, output_price_per_million), currency = COALESCE(NULLIF(?, ''), currency)
                        WHERE id = ?
                        """, request.name(), request.actualModel(), request.enabled() == null ? null : bool(request.enabled(), true),
                request.inputPricePerMillion(), request.outputPricePerMillion(), request.currency(), deploymentId);
        return findDeployment(deploymentId);
    }

    @Transactional
    public void deleteDeployment(long deploymentId) {
        requireDeployment(deploymentId);
        jdbcTemplate.update("DELETE FROM route_target WHERE deployment_id = ?", deploymentId);
        jdbcTemplate.update("DELETE FROM model_deployment WHERE id = ?", deploymentId);
    }

    public LogicalModelListResponse listLogicalModels() {
        List<LogicalModelItem> items = jdbcTemplate.query("""
                        SELECT logical_model, strategy, enabled FROM model_route ORDER BY logical_model
                        """, (rs, rowNum) -> new LogicalModelItem(rs.getString("logical_model"), rs.getInt("enabled") == 1,
                rs.getString("strategy"), targets(rs.getString("logical_model"))));
        return new LogicalModelListResponse(items);
    }

    public LogicalModelItem upsertLogicalModel(UpsertLogicalModelRequest request) {
        jdbcTemplate.update("INSERT IGNORE INTO model(logical_model, created_at) VALUES (?, ?)", request.logicalModel(), now());
        jdbcTemplate.update("""
                        INSERT INTO model_route(logical_model, strategy, enabled, created_at) VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE strategy = VALUES(strategy), enabled = VALUES(enabled)
                        """, request.logicalModel(), blankToDefault(request.strategy(), "WEIGHTED"), bool(request.enabled(), true), now());
        return findLogicalModel(request.logicalModel());
    }

    public LogicalModelItem upsertRouteTarget(String logicalModel, UpsertRouteTargetRequest request) {
        long routeId = routeId(logicalModel);
        requireDeployment(request.deploymentId());
        jdbcTemplate.update("""
                        INSERT INTO route_target(route_id, deployment_id, weight, enabled, created_at) VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE weight = VALUES(weight), enabled = VALUES(enabled)
                        """, routeId, request.deploymentId(), request.weight() == null ? 100 : request.weight(), bool(request.enabled(), true), now());
        return findLogicalModel(logicalModel);
    }

    public ApplicationListResponse listApplications(long teamId) {
        return new ApplicationListResponse(jdbcTemplate.query("""
                        SELECT id, organization_id, team_id, name, created_at FROM application WHERE team_id = ? ORDER BY id ASC
                        """, (rs, rowNum) -> new ApplicationItem(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("team_id"),
                rs.getString("name"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), teamId));
    }

    public ApplicationItem createApplication(long teamId, CreateApplicationRequest request) {
        Long organizationId = jdbcTemplate.queryForObject("SELECT organization_id FROM team WHERE id = ?", Long.class, teamId);
        if (organizationId == null) throw notFound("Team");
        long id = GeneratedKeys.insert(jdbcTemplate, "INSERT INTO application(organization_id, team_id, name, created_at) VALUES (?, ?, ?, ?)",
                organizationId, teamId, request.name(), now());
        return jdbcTemplate.queryForObject("SELECT id, organization_id, team_id, name, created_at FROM application WHERE id = ?",
                (rs, rowNum) -> new ApplicationItem(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("team_id"), rs.getString("name"),
                        JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), id);
    }

    public TeamModelAccessResponse teamModels(long teamId) {
        return new TeamModelAccessResponse(teamId, jdbcTemplate.queryForList(
                "SELECT model_name FROM team_direct_model_access WHERE team_id = ? ORDER BY model_name", String.class, teamId));
    }

    public TeamModelAccessResponse replaceTeamModels(long teamId, List<String> requestedModels) {
        requireTeam(teamId);
        Set<String> target = new LinkedHashSet<>(requestedModels == null ? List.of() : requestedModels);
        List<String> current = teamModels(teamId).logicalModels();
        for (String model : current) {
            if (!target.contains(model)) {
                Integer used = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM virtual_api_key WHERE team_id = ? AND enabled = 1 AND FIND_IN_SET(?, allowed_models) > 0",
                        Integer.class, teamId, model);
                if (used != null && used > 0) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST,
                        "Disable or update active API keys before removing model access: " + model);
            }
        }
        for (String model : target) requireDirectModel(model);
        jdbcTemplate.update("DELETE FROM team_direct_model_access WHERE team_id = ?", teamId);
        for (String model : target) jdbcTemplate.update("INSERT INTO team_direct_model_access(team_id, model_name, created_at) VALUES (?, ?, ?)", teamId, model, now());
        return teamModels(teamId);
    }

    public boolean teamAllowsModels(long teamId, List<String> models) {
        if (models == null || models.isEmpty()) return false;
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team_direct_model_access WHERE team_id = ? AND model_name IN (" + placeholders(models.size()) + ")",
                Integer.class, prepend(teamId, models));
        return count != null && count == new LinkedHashSet<>(models).size();
    }

    public VirtualApiKeyListResponse listKeys(String keyword, Long teamId, Long applicationId, Long memberId, Boolean enabled, String expiry, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT k.id, k.name, k.key_prefix, k.team_id, t.name team_name, k.application_id, a.name application_name,
                       k.owner_member_id, m.name owner_member_name, k.allowed_models, k.enabled, k.expires_at, k.created_at
                FROM virtual_api_key k JOIN team t ON t.id = k.team_id JOIN application a ON a.id = k.application_id
                LEFT JOIN team_member m ON m.id = k.owner_member_id WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND (k.name LIKE ? OR k.key_prefix LIKE ?)"); args.add("%" + keyword.trim() + "%"); args.add("%" + keyword.trim() + "%"); }
        if (teamId != null) { sql.append(" AND k.team_id = ?"); args.add(teamId); }
        if (applicationId != null) { sql.append(" AND k.application_id = ?"); args.add(applicationId); }
        if (memberId != null) { sql.append(" AND k.owner_member_id = ?"); args.add(memberId); }
        if (enabled != null) { sql.append(" AND k.enabled = ?"); args.add(bool(enabled, true)); }
        if ("EXPIRED".equalsIgnoreCase(expiry)) sql.append(" AND k.expires_at IS NOT NULL AND k.expires_at < NOW()");
        if ("ACTIVE".equalsIgnoreCase(expiry)) sql.append(" AND (k.expires_at IS NULL OR k.expires_at >= NOW())");
        long total = longCount("SELECT COUNT(*) " + sql.substring(sql.indexOf("FROM")), args.toArray());
        sql.append(" ORDER BY k.created_at DESC LIMIT ? OFFSET ?");
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<VirtualApiKeyItem> items = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new VirtualApiKeyItem(
                rs.getLong("id"), rs.getString("name"), rs.getString("key_prefix"), rs.getLong("team_id"), rs.getString("team_name"),
                rs.getLong("application_id"), rs.getString("application_name"), nullableLong(rs.getObject("owner_member_id")),
                rs.getString("owner_member_name"), split(rs.getString("allowed_models")), rs.getInt("enabled") == 1,
                JdbcTime.toOffsetDateTime(rs.getTimestamp("expires_at")), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), pageArgs.toArray());
        return new VirtualApiKeyListResponse(items, page, size, total);
    }

    public DashboardOverview dashboard() {
        int providers = count("SELECT COUNT(*) FROM provider WHERE enabled = 1");
        int teams = count("SELECT COUNT(*) FROM team WHERE enabled = 1");
        int keys = count("SELECT COUNT(*) FROM virtual_api_key WHERE enabled = 1");
        long requests = longCount("SELECT COUNT(*) FROM ai_request WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)");
        long successes = longCount("SELECT COUNT(*) FROM ai_request WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR) AND status = 'SUCCESS'");
        long throttled = longCount("SELECT COUNT(*) FROM ai_request WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR) AND error_code IN ('RATE_LIMIT_EXCEEDED', 'CONCURRENCY_LIMIT_EXCEEDED')");
        long frozen = longCount("SELECT COALESCE(SUM(frozen_tokens), 0) FROM quota_account");
        BigDecimal amount = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM billing_record WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)", BigDecimal.class);
        Integer rpm = jdbcTemplate.queryForObject("SELECT global_rpm FROM global_runtime_policy WHERE id = 1", Integer.class);
        Integer concurrency = jdbcTemplate.queryForObject("SELECT global_concurrency FROM global_runtime_policy WHERE id = 1", Integer.class);
        return new DashboardOverview(providers, teams, keys, requests, successes, throttled, frozen, amount, "USD", rpm, concurrency);
    }

    public DashboardOverview updateGlobalPolicy(UpdateGlobalRuntimePolicyRequest request) {
        jdbcTemplate.update("UPDATE global_runtime_policy SET global_rpm = COALESCE(?, global_rpm), global_concurrency = COALESCE(?, global_concurrency), updated_at = ? WHERE id = 1",
                request.globalRpm(), request.globalConcurrency(), now());
        return dashboard();
    }

    public GlobalRuntimePolicy globalRuntimePolicy() {
        Integer rpm = jdbcTemplate.queryForObject("SELECT global_rpm FROM global_runtime_policy WHERE id = 1", Integer.class);
        Integer concurrency = jdbcTemplate.queryForObject("SELECT global_concurrency FROM global_runtime_policy WHERE id = 1", Integer.class);
        return new GlobalRuntimePolicy(rpm == null ? 10000 : rpm, concurrency == null ? 1000 : concurrency);
    }

    private ProviderSummary findProvider(long providerId) {
        return listProviders(null, null, null, 0, 1000).items().stream().filter(item -> item.providerId() == providerId).findFirst().orElseThrow(() -> notFound("Provider"));
    }
    private DeploymentItem findDeployment(long id) {
        return jdbcTemplate.queryForObject("SELECT id, provider_id, name, actual_model, enabled, input_price_per_million, output_price_per_million, currency FROM model_deployment WHERE id = ?",
                (rs, rowNum) -> new DeploymentItem(rs.getLong("id"), rs.getLong("provider_id"), rs.getString("name"), rs.getString("actual_model"),
                        rs.getInt("enabled") == 1, rs.getBigDecimal("input_price_per_million"), rs.getBigDecimal("output_price_per_million"), rs.getString("currency")), id);
    }
    private LogicalModelItem findLogicalModel(String name) {
        return listLogicalModels().items().stream().filter(item -> item.logicalModel().equals(name)).findFirst().orElseThrow(() -> notFound("Logical model"));
    }
    private List<RouteTargetItem> targets(String logicalModel) {
        return jdbcTemplate.query("""
                        SELECT d.id deployment_id, d.name deployment_name, p.name provider_name, rt.weight, rt.enabled
                        FROM model_route r JOIN route_target rt ON rt.route_id = r.id JOIN model_deployment d ON d.id = rt.deployment_id
                        JOIN provider p ON p.id = d.provider_id WHERE r.logical_model = ? ORDER BY rt.weight DESC, rt.id ASC
                        """, (rs, rowNum) -> new RouteTargetItem(rs.getLong("deployment_id"), rs.getString("deployment_name"),
                rs.getString("provider_name"), rs.getInt("weight"), rs.getInt("enabled") == 1), logicalModel);
    }
    private long routeId(String logicalModel) {
        try { Long id = jdbcTemplate.queryForObject("SELECT id FROM model_route WHERE logical_model = ?", Long.class, logicalModel); if (id == null) throw notFound("Logical model"); return id; }
        catch (EmptyResultDataAccessException ex) { throw notFound("Logical model"); }
    }
    private void requireDirectModel(String modelName) { Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_model WHERE model_name = ? AND enabled = 1", Integer.class, modelName); if (count == null || count == 0) throw notFound("Direct model"); }
    private void requireProvider(long id) { findProvider(id); }
    private void requireDeployment(long id) { findDeployment(id); }
    private void requireTeam(long id) { if (count("SELECT COUNT(*) FROM team WHERE id = " + id) == 0) throw notFound("Team"); }
    private int count(String sql) { Integer value = jdbcTemplate.queryForObject(sql, Integer.class); return value == null ? 0 : value; }
    private long longCount(String sql) { Long value = jdbcTemplate.queryForObject(sql, Long.class); return value == null ? 0L : value; }
    private long longCount(String sql, Object... args) { Long value = jdbcTemplate.queryForObject(sql, Long.class, args); return value == null ? 0L : value; }
    private static ModelGateException notFound(String type) { return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, type + " was not found."); }
    private static java.sql.Timestamp now() { return JdbcTime.toTimestamp(OffsetDateTime.now()); }
    private static int bool(Boolean value, boolean fallback) { return Boolean.TRUE.equals(value == null ? fallback : value) ? 1 : 0; }
    private static BigDecimal price(BigDecimal value) { return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO); }
    private static String currency(String value) { return blankToDefault(value, "USD").toUpperCase(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String blankToDefault(String value, String fallback) { String normalized = blankToNull(value); return normalized == null ? fallback : normalized; }
    private static String providerType(String value) { String normalized = blankToDefault(value, "MOCK_OPENAI"); if (!"MOCK_OPENAI".equals(normalized) && !"OPENAI_COMPATIBLE".equals(normalized)) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Provider type must be MOCK_OPENAI or OPENAI_COMPATIBLE."); return normalized; }
    private static String providerTypeOrNull(String value) { return blankToNull(value) == null ? null : providerType(value); }
    private static List<String> split(String value) { return value == null || value.isBlank() ? List.of() : List.of(value.split(",")); }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static Object[] prepend(long first, List<String> values) { Object[] args = new Object[values.size() + 1]; args[0] = first; for (int i = 0; i < values.size(); i++) args[i + 1] = values.get(i); return args; }
}
