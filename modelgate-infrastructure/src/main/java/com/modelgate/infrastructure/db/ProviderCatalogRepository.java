package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.DirectModelItem;
import com.modelgate.common.api.AdminDtos.DirectModelListResponse;
import com.modelgate.common.api.AdminDtos.ProviderCredentialItem;
import com.modelgate.common.api.AdminDtos.ProviderCredentialListResponse;
import com.modelgate.common.api.AdminDtos.UpsertDirectModelRequest;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ProviderCatalogRepository {
    private final JdbcTemplate jdbcTemplate;

    public ProviderCatalogRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public ProviderCredentialListResponse credentials(long providerId) {
        requireProvider(providerId);
        return new ProviderCredentialListResponse(jdbcTemplate.query("""
                SELECT id, provider_id, name, key_last_four, enabled, updated_at FROM provider_credential
                WHERE provider_id = ? ORDER BY id
                """, (rs, rowNum) -> new ProviderCredentialItem(rs.getLong("id"), rs.getLong("provider_id"),
                rs.getString("name"), rs.getString("key_last_four"), rs.getInt("enabled") == 1,
                JdbcTime.toOffsetDateTime(rs.getTimestamp("updated_at"))), providerId));
    }

    public ProviderCredentialItem createCredential(long providerId, String name, String ciphertext, String version, String lastFour, boolean enabled) {
        requireProvider(providerId);
        long id = GeneratedKeys.insert(jdbcTemplate, """
                INSERT INTO provider_credential(provider_id, name, api_key_ciphertext, key_version, key_last_four, enabled, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, providerId, name.trim(), ciphertext, version, lastFour, enabled ? 1 : 0, now());
        return credential(id);
    }

    public ProviderCredentialItem updateCredential(long credentialId, String name, String ciphertext, String version, String lastFour, Boolean enabled) {
        int changed = jdbcTemplate.update("""
                UPDATE provider_credential SET name = COALESCE(NULLIF(?, ''), name),
                  api_key_ciphertext = COALESCE(?, api_key_ciphertext), key_version = COALESCE(?, key_version),
                  key_last_four = COALESCE(?, key_last_four), enabled = COALESCE(?, enabled), updated_at = ?
                WHERE id = ?
                """, name, ciphertext, version, lastFour, enabled == null ? null : enabled ? 1 : 0, now(), credentialId);
        if (changed == 0) throw notFound("Provider credential");
        return credential(credentialId);
    }

    public void disableCredential(long credentialId) {
        if (jdbcTemplate.update("UPDATE provider_credential SET enabled = 0, updated_at = ? WHERE id = ?", now(), credentialId) == 0) throw notFound("Provider credential");
    }

    public DirectModelListResponse models() {
        return new DirectModelListResponse(jdbcTemplate.query(modelSql() + " ORDER BY pm.model_name", (rs, rowNum) -> mapModel(rs)));
    }

    public DirectModelItem createModel(UpsertDirectModelRequest request) {
        requireProvider(request.providerId());
        try {
            long id = GeneratedKeys.insert(jdbcTemplate, """
                    INSERT INTO provider_model(provider_id, model_name, enabled, input_price_per_million, output_price_per_million, currency, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, request.providerId(), request.modelName().trim(), bool(request.enabled(), true), price(request.inputPricePerMillion()),
                    price(request.outputPricePerMillion()), currency(request.currency()), now(), now());
            return model(id);
        } catch (org.springframework.dao.DuplicateKeyException ex) { throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "An enabled or disabled direct model already uses this name."); }
    }

    public DirectModelItem updateModel(long modelId, UpsertDirectModelRequest request) {
        requireProvider(request.providerId());
        int changed = jdbcTemplate.update("""
                UPDATE provider_model SET provider_id = ?, model_name = ?, enabled = ?, input_price_per_million = ?,
                  output_price_per_million = ?, currency = ?, updated_at = ? WHERE id = ?
                """, request.providerId(), request.modelName().trim(), bool(request.enabled(), true), price(request.inputPricePerMillion()),
                price(request.outputPricePerMillion()), currency(request.currency()), now(), modelId);
        if (changed == 0) throw notFound("Direct model");
        return model(modelId);
    }

    public void deleteModel(long modelId) {
        DirectModelItem model = model(modelId);
        Integer teamUse = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team_direct_model_access WHERE model_name = ?", Integer.class, model.modelName());
        if (teamUse != null && teamUse > 0) throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Remove team access before deleting this model.");
        jdbcTemplate.update("DELETE FROM provider_model WHERE id = ?", modelId);
    }

    public List<EncryptedCredential> enabledCredentials(long providerId) {
        return jdbcTemplate.query("""
                SELECT id, api_key_ciphertext, key_version FROM provider_credential
                WHERE provider_id = ? AND enabled = 1 ORDER BY id
                """, (rs, rowNum) -> new EncryptedCredential(rs.getLong("id"), rs.getString("api_key_ciphertext"), rs.getString("key_version")), providerId);
    }

    private String modelSql() { return """
            SELECT pm.id, pm.provider_id, p.name provider_name, pm.model_name, pm.enabled, pm.input_price_per_million,
              pm.output_price_per_million, pm.currency FROM provider_model pm JOIN provider p ON p.id = pm.provider_id
            """; }
    private DirectModelItem model(long id) { try { return jdbcTemplate.queryForObject(modelSql() + " WHERE pm.id = ?", (rs, rowNum) -> mapModel(rs), id); } catch (EmptyResultDataAccessException ex) { throw notFound("Direct model"); } }
    private DirectModelItem mapModel(java.sql.ResultSet rs) throws java.sql.SQLException { return new DirectModelItem(rs.getLong("id"), rs.getLong("provider_id"), rs.getString("provider_name"), rs.getString("model_name"), rs.getInt("enabled") == 1, rs.getBigDecimal("input_price_per_million"), rs.getBigDecimal("output_price_per_million"), rs.getString("currency")); }
    private ProviderCredentialItem credential(long id) { try { return jdbcTemplate.queryForObject("SELECT id, provider_id, name, key_last_four, enabled, updated_at FROM provider_credential WHERE id = ?", (rs, rowNum) -> new ProviderCredentialItem(rs.getLong("id"), rs.getLong("provider_id"), rs.getString("name"), rs.getString("key_last_four"), rs.getInt("enabled") == 1, JdbcTime.toOffsetDateTime(rs.getTimestamp("updated_at"))), id); } catch (EmptyResultDataAccessException ex) { throw notFound("Provider credential"); } }
    private void requireProvider(long providerId) { try { jdbcTemplate.queryForObject("SELECT id FROM provider WHERE id = ?", Long.class, providerId); } catch (EmptyResultDataAccessException ex) { throw notFound("Provider"); } }
    private static int bool(Boolean value, boolean fallback) { return Boolean.TRUE.equals(value == null ? fallback : value) ? 1 : 0; }
    private static BigDecimal price(BigDecimal value) { return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO); }
    private static String currency(String value) { return value == null || value.isBlank() ? "USD" : value.trim().toUpperCase(); }
    private static java.sql.Timestamp now() { return JdbcTime.toTimestamp(OffsetDateTime.now()); }
    private static ModelGateException notFound(String type) { return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, type + " was not found."); }
    public record EncryptedCredential(long credentialId, String ciphertext, String version) { }
}
