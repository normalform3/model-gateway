package com.modelgate.infrastructure.db;

import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.common.api.AdminDtos.*;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class BillingRepository {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RANGE_DAYS = 366;
    private static final int RANKING_LIMIT = 10;
    private static final String BILLING_FROM = """
            FROM billing_record b
            LEFT JOIN team t ON t.id = b.team_id
            LEFT JOIN project p ON p.id = b.project_id
            LEFT JOIN team_member m ON m.id = b.member_id
            """;
    private final JdbcTemplate jdbcTemplate;

    public BillingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean markConsumed(String eventId, String consumerGroup) {
        try {
            jdbcTemplate.update("INSERT INTO mq_consume_record(event_id, consumer_group, consumed_at) VALUES (?, ?, ?)",
                    eventId, consumerGroup, JdbcTime.toTimestamp(OffsetDateTime.now()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void insertUsage(UsageCompletedEvent event) {
        jdbcTemplate.update("""
                        INSERT IGNORE INTO usage_record(
                            event_id, request_id, organization_id, team_id, api_key_id,
                            member_id, credential_type, project_id, service_account_id, provider, model, input_tokens, output_tokens, total_tokens, usage_source, status, occurred_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.eventId(),
                event.requestId(),
                event.organizationId(),
                event.teamId(),
                event.apiKeyId(),
                event.memberId(),
                event.credentialType().name(),
                event.projectId(),
                event.serviceAccountId(),
                event.provider(),
                event.actualModel(),
                event.inputTokens(),
                event.outputTokens(),
                event.totalTokens(),
                "PROVIDER",
                event.status(),
                JdbcTime.toTimestamp(event.occurredAt()));
    }

    public void insertBilling(UsageCompletedEvent event) {
        Pricing pricing = findPricing(event.provider(), event.actualModel());
        BigDecimal amount = pricing.inputPricePerMillion()
                .multiply(BigDecimal.valueOf(event.inputTokens())).movePointLeft(6)
                .add(pricing.outputPricePerMillion().multiply(BigDecimal.valueOf(event.outputTokens())).movePointLeft(6));
        jdbcTemplate.update("""
                        INSERT IGNORE INTO billing_record(
                            request_id, organization_id, team_id, api_key_id,
                            member_id, credential_type, project_id, service_account_id, provider, model, input_tokens, output_tokens, unit_price, amount, currency, billing_type, created_at
                            , input_unit_price, output_unit_price
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.requestId(),
                event.organizationId(),
                event.teamId(),
                event.apiKeyId(),
                event.memberId(),
                event.credentialType().name(),
                event.projectId(),
                event.serviceAccountId(),
                event.provider(),
                event.actualModel(),
                event.inputTokens(),
                event.outputTokens(),
                pricing.inputPricePerMillion().add(pricing.outputPricePerMillion()),
                amount,
                pricing.currency(),
                "USAGE",
                JdbcTime.toTimestamp(OffsetDateTime.now()),
                pricing.inputPricePerMillion(),
                pricing.outputPricePerMillion());
    }

    public void insertQuotaConsumeTransaction(UsageCompletedEvent event) {
        String accountType = "APPLICATION".equals(event.credentialType().name()) ? "PROJECT_APPLICATION" : "MEMBER_DEVELOPMENT";
        Long ownerId = "APPLICATION".equals(event.credentialType().name()) ? event.projectId() : event.memberId();
        Long accountId = jdbcTemplate.queryForObject("SELECT id FROM quota_account WHERE account_type = ? AND owner_id = ?", Long.class, accountType, ownerId);
        Long balanceAfter = jdbcTemplate.queryForObject(
                "SELECT available_tokens FROM quota_account WHERE id = ?",
                Long.class,
                accountId);
        jdbcTemplate.update("""
                        INSERT IGNORE INTO quota_transaction(
                            transaction_no, account_id, request_id, transaction_type, amount, balance_after, event_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                "qt-" + event.eventId() + "-consume",
                accountId,
                event.requestId(),
                event.credentialType().name() + "_CONSUME",
                event.totalTokens(),
                balanceAfter == null ? 0L : balanceAfter,
                event.eventId(),
                JdbcTime.toTimestamp(OffsetDateTime.now()));
    }

    public BillingSummary teamSummary(long teamId) {
        return summary("team_id", teamId);
    }

    public BillingSummary memberSummary(long memberId) {
        return summary("member_id", memberId);
    }

    /** The workbench only reads durable billing facts and never converts currencies. */
    public BillingOverview overview(BillingQuery request) {
        BillingFilter filter = filter(request);
        Totals totals = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(b.input_tokens + b.output_tokens), 0) total_tokens, COUNT(*) record_count "
                        + BILLING_FROM + filter.where(),
                (rs, row) -> new Totals(rs.getLong("total_tokens"), rs.getLong("record_count")), filter.argsArray());
        if (totals == null) totals = new Totals(0, 0);
        return new BillingOverview(filter.from(), filter.to(), totals.totalTokens(), totals.recordCount(),
                currencyAmounts(filter), dailyTrends(filter), dimensions(filter, Dimension.TEAM),
                dimensions(filter, Dimension.PROJECT), dimensions(filter, Dimension.MEMBER), dimensions(filter, Dimension.MODEL));
    }

    public BillingRecordPage records(BillingQuery request, int page, int size) {
        BillingFilter filter = filter(request);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + BILLING_FROM + filter.where(), Long.class, filter.argsArray());
        List<Object> args = filter.mutableArgs();
        args.add(safeSize);
        args.add(safePage * safeSize);
        List<BillingRecordItem> items = jdbcTemplate.query("""
                SELECT b.request_id, b.team_id, t.name AS team_name, b.project_id, p.name AS project_name,
                       b.member_id, m.name AS member_name, b.credential_type, b.provider, b.model,
                       b.input_tokens, b.output_tokens, b.input_unit_price, b.output_unit_price,
                       b.amount, b.currency, b.created_at
                """ + BILLING_FROM + filter.where() + " ORDER BY b.created_at DESC, b.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new BillingRecordItem(
                        rs.getString("request_id"), nullableLong(rs, "team_id"), named(rs.getString("team_name"), "团队", nullableLong(rs, "team_id")),
                        nullableLong(rs, "project_id"), named(rs.getString("project_name"), "项目", nullableLong(rs, "project_id")),
                        nullableLong(rs, "member_id"), named(rs.getString("member_name"), "成员", nullableLong(rs, "member_id")),
                        rs.getString("credential_type"), rs.getString("provider"), rs.getString("model"),
                        rs.getInt("input_tokens"), rs.getInt("output_tokens"), rs.getBigDecimal("input_unit_price"),
                        rs.getBigDecimal("output_unit_price"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at"))), args.toArray());
        return new BillingRecordPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    private BillingSummary summary(String column, long scopeId) {
        String sql = """
                SELECT COALESCE(SUM(input_tokens + output_tokens), 0) total_tokens,
                       COALESCE(SUM(amount), 0) total_amount,
                       COALESCE(MAX(currency), 'USD') currency,
                       COUNT(*) record_count
                FROM billing_record
                """ + " WHERE " + column + " = ?";
        return jdbcTemplate.queryForObject(sql, (rs, row) -> new BillingSummary(scopeId,
                rs.getLong("total_tokens"), rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getLong("record_count")), scopeId);
    }

    private List<BillingCurrencyAmount> currencyAmounts(BillingFilter filter) {
        return jdbcTemplate.query("SELECT b.currency, COALESCE(SUM(b.amount), 0) amount " + BILLING_FROM + filter.where()
                        + " GROUP BY b.currency ORDER BY b.currency",
                (rs, row) -> new BillingCurrencyAmount(rs.getString("currency"), rs.getBigDecimal("amount")), filter.argsArray());
    }

    private List<BillingDailyTrend> dailyTrends(BillingFilter filter) {
        List<DayCurrency> rows = jdbcTemplate.query("SELECT DATE(b.created_at) day, b.currency, COALESCE(SUM(b.input_tokens + b.output_tokens), 0) total_tokens, COUNT(*) record_count, COALESCE(SUM(b.amount), 0) amount "
                        + BILLING_FROM + filter.where() + " GROUP BY DATE(b.created_at), b.currency ORDER BY day, b.currency",
                (rs, row) -> new DayCurrency(rs.getDate("day").toLocalDate(), rs.getString("currency"), rs.getLong("total_tokens"), rs.getLong("record_count"), rs.getBigDecimal("amount")), filter.argsArray());
        Map<LocalDate, DayAccumulator> grouped = new LinkedHashMap<>();
        for (DayCurrency row : rows) grouped.computeIfAbsent(row.day(), DayAccumulator::new).add(row);
        return grouped.values().stream().map(DayAccumulator::item).toList();
    }

    private List<BillingDimensionItem> dimensions(BillingFilter filter, Dimension dimension) {
        List<DimensionCurrency> rows = jdbcTemplate.query("SELECT " + dimension.select() + ", b.currency, "
                        + "COALESCE(SUM(b.input_tokens + b.output_tokens), 0) total_tokens, COUNT(*) record_count, COALESCE(SUM(b.amount), 0) amount "
                        + BILLING_FROM + filter.where() + dimension.extraWhere() + " GROUP BY " + dimension.groupBy() + ", b.currency",
                (rs, row) -> new DimensionCurrency(nullableLong(rs, "dimension_id"), nullableLong(rs, "team_id"), rs.getString("label"), rs.getString("provider"),
                        rs.getString("model"), rs.getString("currency"), rs.getLong("total_tokens"), rs.getLong("record_count"), rs.getBigDecimal("amount")), filter.argsArray());
        Map<String, DimensionAccumulator> grouped = new LinkedHashMap<>();
        for (DimensionCurrency row : rows) grouped.computeIfAbsent(row.key(), ignored -> new DimensionAccumulator(row)).add(row);
        return grouped.values().stream().map(DimensionAccumulator::item)
                .sorted(Comparator.comparingLong(BillingDimensionItem::totalTokens).reversed().thenComparing(BillingDimensionItem::label))
                .limit(RANKING_LIMIT).toList();
    }

    private BillingFilter filter(BillingQuery request) {
        BillingQuery input = request == null ? new BillingQuery(null, null, null, null, null, null, null, null, null) : request;
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = input.from() == null ? today.withDayOfMonth(1) : input.from();
        LocalDate to = input.to() == null ? today : input.to();
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw badRequest("Billing date range must be ordered and no longer than 366 days.");
        }
        String credentialType = upper(input.credentialType());
        if (credentialType != null && !credentialType.equals("DEVELOPER") && !credentialType.equals("APPLICATION")) {
            throw badRequest("credentialType must be DEVELOPER or APPLICATION.");
        }
        StringBuilder where = new StringBuilder(" WHERE b.created_at >= ? AND b.created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(from.atStartOfDay()));
        args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        appendId(where, args, "b.team_id", input.teamId(), "teamId");
        appendId(where, args, "b.project_id", input.projectId(), "projectId");
        appendId(where, args, "b.member_id", input.memberId(), "memberId");
        appendText(where, args, "b.provider", trimmed(input.provider()));
        appendText(where, args, "b.model", trimmed(input.model()));
        appendText(where, args, "b.credential_type", credentialType);
        appendText(where, args, "b.currency", trimmed(input.currency()));
        return new BillingFilter(from, to, where.toString(), args);
    }

    private static void appendId(StringBuilder where, List<Object> args, String column, Long value, String name) {
        if (value == null) return;
        if (value <= 0) throw badRequest(name + " must be positive.");
        where.append(" AND ").append(column).append(" = ?");
        args.add(value);
    }

    private static void appendText(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null) return;
        where.append(" AND ").append(column).append(" = ?");
        args.add(value);
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String upper(String value) {
        String normalized = trimmed(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String named(String value, String kind, Long id) {
        return value == null || value.isBlank() ? (id == null ? "未归属" : kind + " #" + id) : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static ModelGateException badRequest(String message) {
        return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, message);
    }

    private record BillingFilter(LocalDate from, LocalDate to, String where, List<Object> args) {
        Object[] argsArray() { return args.toArray(); }
        List<Object> mutableArgs() { return new ArrayList<>(args); }
    }

    private record Totals(long totalTokens, long recordCount) { }
    private record DayCurrency(LocalDate day, String currency, long totalTokens, long recordCount, BigDecimal amount) { }
    private record DimensionCurrency(Long id, Long teamId, String label, String provider, String model, String currency, long totalTokens, long recordCount, BigDecimal amount) {
        String key() { return id == null ? provider + "\u0000" + model : String.valueOf(id); }
    }

    private static final class DayAccumulator {
        private final LocalDate day;
        private long totalTokens;
        private long recordCount;
        private final List<BillingCurrencyAmount> amounts = new ArrayList<>();
        private DayAccumulator(LocalDate day) { this.day = day; }
        private void add(DayCurrency row) { totalTokens += row.totalTokens(); recordCount += row.recordCount(); amounts.add(new BillingCurrencyAmount(row.currency(), row.amount())); }
        private BillingDailyTrend item() { return new BillingDailyTrend(day, totalTokens, recordCount, List.copyOf(amounts)); }
    }

    private static final class DimensionAccumulator {
        private final Long id;
        private final Long teamId;
        private final String label;
        private final String provider;
        private final String model;
        private long totalTokens;
        private long recordCount;
        private final List<BillingCurrencyAmount> amounts = new ArrayList<>();
        private DimensionAccumulator(DimensionCurrency row) { id = row.id(); teamId = row.teamId(); label = row.label(); provider = row.provider(); model = row.model(); }
        private void add(DimensionCurrency row) { totalTokens += row.totalTokens(); recordCount += row.recordCount(); amounts.add(new BillingCurrencyAmount(row.currency(), row.amount())); }
        private BillingDimensionItem item() { return new BillingDimensionItem(id, teamId, label, provider, model, totalTokens, recordCount, List.copyOf(amounts)); }
    }

    private enum Dimension {
        TEAM("b.team_id AS dimension_id, b.team_id AS team_id, COALESCE(t.name, CONCAT('团队 #', b.team_id)) AS label, NULL AS provider, NULL AS model", "b.team_id, t.name", ""),
        PROJECT("b.project_id AS dimension_id, b.team_id AS team_id, COALESCE(p.name, CONCAT('项目 #', b.project_id)) AS label, NULL AS provider, NULL AS model", "b.project_id, b.team_id, p.name", " AND b.project_id IS NOT NULL"),
        MEMBER("b.member_id AS dimension_id, b.team_id AS team_id, COALESCE(m.name, CONCAT('成员 #', b.member_id)) AS label, NULL AS provider, NULL AS model", "b.member_id, b.team_id, m.name", " AND b.member_id IS NOT NULL"),
        MODEL("NULL AS dimension_id, NULL AS team_id, CONCAT(b.provider, ' / ', b.model) AS label, b.provider AS provider, b.model AS model", "b.provider, b.model", "");
        private final String select;
        private final String groupBy;
        private final String extraWhere;
        Dimension(String select, String groupBy, String extraWhere) { this.select = select; this.groupBy = groupBy; this.extraWhere = extraWhere; }
        String select() { return select; }
        String groupBy() { return groupBy; }
        String extraWhere() { return extraWhere; }
    }

    private Pricing findPricing(String provider, String model) {
        try {
            return jdbcTemplate.queryForObject("""
                            SELECT pm.input_price_per_million, pm.output_price_per_million, pm.currency
                            FROM provider_model pm JOIN provider p ON p.id = pm.provider_id
                            WHERE p.name = ? AND pm.model_name = ? ORDER BY pm.id ASC LIMIT 1
                            """, (rs, rowNum) -> new Pricing(rs.getBigDecimal("input_price_per_million"),
                    rs.getBigDecimal("output_price_per_million"), rs.getString("currency")), provider, model);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return new Pricing(BigDecimal.ZERO, BigDecimal.ZERO, "USD");
        }
    }

    private record Pricing(BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, String currency) {
    }
}
