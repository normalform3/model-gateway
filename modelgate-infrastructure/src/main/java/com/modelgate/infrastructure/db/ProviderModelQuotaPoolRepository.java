package com.modelgate.infrastructure.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Fixed-model external account pool; it deliberately never chooses another model or Provider. */
@Repository
public class ProviderModelQuotaPoolRepository {
    private final JdbcTemplate jdbc;
    public ProviderModelQuotaPoolRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public long ensurePool(long modelId) {
        jdbc.update("INSERT IGNORE INTO provider_model_quota_pool(provider_model_id,enabled,created_at,updated_at) VALUES (?,1,?,?)",modelId,now(),now());
        return jdbc.queryForObject("SELECT id FROM provider_model_quota_pool WHERE provider_model_id=?",Long.class,modelId);
    }
    public void attach(long modelId,long credentialId,long availableTokens) { long pool=ensurePool(modelId); jdbc.update("INSERT INTO provider_model_pool_credential(pool_id,credential_id,available_tokens,frozen_tokens,consumed_tokens,enabled,health_status,updated_at) VALUES (?,?,?,0,0,1,'HEALTHY',?) ON DUPLICATE KEY UPDATE available_tokens=VALUES(available_tokens),enabled=1,health_status='HEALTHY',updated_at=VALUES(updated_at)",pool,credentialId,availableTokens,now()); }
    public void update(long poolId,long credentialId,long availableTokens,boolean enabled) { jdbc.update("UPDATE provider_model_pool_credential SET available_tokens=?,enabled=?,updated_at=? WHERE pool_id=? AND credential_id=?",availableTokens,enabled?1:0,now(),poolId,credentialId); }
    public Optional<Long> nextCredential(long modelId, List<Long> excluded) {
        String excludedSql=excluded.isEmpty()?"":" AND pc.credential_id NOT IN ("+excluded.stream().map(id->"?").collect(java.util.stream.Collectors.joining(","))+")";
        java.util.ArrayList<Object> args=new java.util.ArrayList<>();args.add(modelId);args.addAll(excluded);
        return jdbc.query("SELECT pc.credential_id FROM provider_model_quota_pool p JOIN provider_model_pool_credential pc ON pc.pool_id=p.id WHERE p.provider_model_id=? AND p.enabled=1 AND pc.enabled=1 AND pc.health_status='HEALTHY' AND pc.available_tokens>0"+excludedSql+" ORDER BY pc.updated_at,pc.credential_id LIMIT 1",(rs,row)->rs.getLong(1),args.toArray()).stream().findFirst();
    }
    public boolean configured(long modelId) { Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM provider_model_quota_pool WHERE provider_model_id=? AND enabled=1",Integer.class,modelId); return count!=null&&count>0; }
    private static java.sql.Timestamp now(){return JdbcTime.toTimestamp(OffsetDateTime.now());}
}
