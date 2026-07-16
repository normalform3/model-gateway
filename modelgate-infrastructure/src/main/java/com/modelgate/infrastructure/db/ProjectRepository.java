package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.*;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;

/** Control-plane persistence for the application quota hierarchy. */
@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;
    public ProjectRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public ProjectItem create(long teamId, CreateProjectRequest request) {
        Team team = team(teamId);
        long id = GeneratedKeys.insert(jdbc, "INSERT INTO project(organization_id,team_id,name,project_code,enabled,created_at,updated_at) VALUES (?,?,?,?,1,?,?)",
                team.organizationId(), teamId, request.name().trim(), request.projectCode().trim(), now(), now());
        account("PROJECT_APPLICATION", id);
        return project(id);
    }
    public ProjectListResponse list(long teamId) { return new ProjectListResponse(jdbc.query("SELECT id,team_id,name,project_code,enabled,created_at FROM project WHERE team_id=? ORDER BY id DESC", (rs,row)->new ProjectItem(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getInt(5)==1,JdbcTime.toOffsetDateTime(rs.getTimestamp(6))), teamId)); }
    public ProjectItem update(long teamId, long projectId, UpdateProjectRequest request) { requireProject(teamId, projectId); jdbc.update("UPDATE project SET name=COALESCE(NULLIF(?,''),name), enabled=COALESCE(?,enabled), updated_at=? WHERE id=?", request.name(), request.enabled()==null?null:request.enabled()?1:0, now(), projectId); return project(projectId); }

    @Transactional
    public ProjectQuotaResponse allocate(long teamId, long projectId, ProjectQuotaRequest request) {
        requireOwner(teamId, request.ownerMemberId()); requireProject(teamId, projectId);
        if (request.tokenAllocation() <= 0) throw bad("tokenAllocation must be positive.");
        account("TEAM_APPLICATION", teamId); account("PROJECT_APPLICATION", projectId);
        long from = id("SELECT id FROM quota_account WHERE account_type='TEAM_APPLICATION' AND owner_id=?", teamId);
        long to = id("SELECT id FROM quota_account WHERE account_type='PROJECT_APPLICATION' AND owner_id=?", projectId);
        Long available = jdbc.queryForObject("SELECT available_tokens FROM quota_account WHERE id=? FOR UPDATE", Long.class, from);
        if (available == null || available < request.tokenAllocation()) throw new ModelGateException(ErrorCode.QUOTA_INSUFFICIENT, "The team application pool does not have enough quota.");
        jdbc.update("UPDATE quota_account SET available_tokens=available_tokens-?,version=version+1,updated_at=? WHERE id=?", request.tokenAllocation(),now(),from);
        jdbc.update("UPDATE quota_account SET available_tokens=available_tokens+?,version=version+1,updated_at=? WHERE id=?", request.tokenAllocation(),now(),to);
        for (String model : models(request.modelNames())) {
            long parent = requireTeamApplicationModel(teamId, model);
            Long parentLimit = jdbc.queryForObject("SELECT quota_limit FROM model_entitlement_grant WHERE id=?",Long.class,parent);
            Long allocated = jdbc.queryForObject("SELECT COALESCE(SUM(quota_limit),0) FROM model_entitlement_grant WHERE team_id=? AND project_id IS NOT NULL AND model_name=? AND pool_type='APPLICATION' AND status='ACTIVE'",Long.class,teamId,model);
            if (parentLimit != null && (allocated == null ? 0 : allocated) + request.tokenAllocation() > parentLimit) throw new ModelGateException(ErrorCode.QUOTA_INSUFFICIENT,"Project allocations exceed the team application quota for "+model);
            jdbc.update("INSERT INTO project_model_access(project_id,model_name,created_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE created_at=VALUES(created_at)", projectId,model,now());
            jdbc.update("INSERT INTO model_entitlement_grant(team_id,member_id,project_id,model_name,pool_type,quota_mode,quota_limit,status,reason,created_at) VALUES (?,NULL,?,?, 'APPLICATION','DAILY',?,'ACTIVE',?,?) ON DUPLICATE KEY UPDATE quota_limit=VALUES(quota_limit),reason=VALUES(reason)",teamId,projectId,model,request.tokenAllocation(),request.reason(),now());
        }
        long balance = id("SELECT available_tokens FROM quota_account WHERE id=?",to);
        jdbc.update("INSERT INTO quota_transfer(transfer_no,team_id,member_id,project_id,from_account_id,to_account_id,amount,pool_type,reason,created_at) VALUES (?,?,NULL,?,?,?,?,'APPLICATION',?,?)", "qt-project-"+java.util.UUID.randomUUID(),teamId,projectId,from,to,request.tokenAllocation(),request.reason()==null?"Project allocation":request.reason(),now());
        return new ProjectQuotaResponse(projectId,to,balance,models(request.modelNames()));
    }

    @Transactional
    public void grantTeamApplicationPool(long teamId, GrantApplicationPoolRequest request) {
        if (request.tokenAllocation() <= 0) throw bad("tokenAllocation must be positive.");
        account("TEAM_APPLICATION", teamId);
        for (String model : models(request.modelNames())) {
            Integer enabled=jdbc.queryForObject("SELECT COUNT(*) FROM provider_model WHERE model_name=? AND enabled=1",Integer.class,model);
            if(enabled==null||enabled==0)throw bad("Model was not found or disabled: "+model);
            jdbc.update("INSERT INTO model_entitlement_grant(team_id,member_id,project_id,model_name,pool_type,quota_mode,quota_limit,status,reason,created_at) VALUES (?,NULL,NULL,?,'APPLICATION','DAILY',?,'ACTIVE',?,?) ON DUPLICATE KEY UPDATE quota_limit=VALUES(quota_limit),reason=VALUES(reason)",teamId,model,request.tokenAllocation(),request.reason(),now());
        }
        long account=id("SELECT id FROM quota_account WHERE account_type='TEAM_APPLICATION' AND owner_id=?",teamId);
        jdbc.update("UPDATE quota_account SET available_tokens=available_tokens+?,version=version+1,updated_at=? WHERE id=?",request.tokenAllocation(),now(),account);
    }

    @Transactional
    public ProjectServiceAccountItem createServiceAccount(long teamId,long projectId,CreateProjectServiceAccountRequest request) { requireProject(teamId,projectId); long id=GeneratedKeys.insert(jdbc,"INSERT INTO project_service_account(project_id,name,enabled,created_at,updated_at) VALUES (?,?,1,?,?)",projectId,request.name().trim(),now(),now()); return serviceAccount(id); }
    public ProjectServiceAccountListResponse serviceAccounts(long teamId, long projectId) {
        requireProject(teamId, projectId);
        return new ProjectServiceAccountListResponse(jdbc.query("""
                SELECT sa.id, sa.project_id, sa.name, sa.enabled, sa.created_at,
                       k.id AS key_id, k.key_prefix, k.enabled AS key_enabled, k.created_at AS key_created_at
                FROM project_service_account sa
                LEFT JOIN virtual_api_key k ON k.id = (
                    SELECT latest.id FROM virtual_api_key latest
                    WHERE latest.service_account_id = sa.id AND latest.credential_type = 'APPLICATION'
                    ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1
                )
                WHERE sa.project_id = ?
                ORDER BY sa.id DESC
                """, (rs,row) -> new ProjectServiceAccountStatusItem(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"), rs.getBoolean("enabled"), JdbcTime.toOffsetDateTime(rs.getTimestamp("created_at")),
                nullableLong(rs, "key_id"), rs.getString("key_prefix"), rs.getBoolean("key_enabled"), JdbcTime.toOffsetDateTime(rs.getTimestamp("key_created_at"))), projectId));
    }
    @Transactional
    public ProjectServiceAccountItem updateServiceAccount(long teamId, long projectId, long serviceAccountId, UpdateProjectServiceAccountRequest request) {
        requireProject(teamId, projectId);
        int changed = jdbc.update("UPDATE project_service_account SET enabled=?,updated_at=? WHERE id=? AND project_id=?", request.enabled() ? 1 : 0, now(), serviceAccountId, projectId);
        if (changed == 0) throw bad("Service account was not found.");
        if (!request.enabled()) jdbc.update("UPDATE virtual_api_key SET enabled=0 WHERE service_account_id=? AND credential_type='APPLICATION'", serviceAccountId);
        return serviceAccount(serviceAccountId);
    }
    public long insertApplicationKey(long serviceAccountId, String keyPrefix, String keyHash) {
        ServiceScope scope = jdbc.queryForObject("SELECT p.organization_id,p.team_id,sa.project_id FROM project_service_account sa JOIN project p ON p.id=sa.project_id WHERE sa.id=? AND sa.enabled=1 AND p.enabled=1", (rs,row)->new ServiceScope(rs.getLong(1),rs.getLong(2),rs.getLong(3)),serviceAccountId);
        if (scope == null) throw bad("Service account was not found or is disabled.");
        return GeneratedKeys.insert(jdbc,"INSERT INTO virtual_api_key(organization_id,team_id,owner_member_id,project_id,service_account_id,name,key_prefix,key_hash,key_kind,credential_type,enabled,created_at) VALUES (?,?,NULL,?,?,?, ?,?,'STANDARD','APPLICATION',1,?)",scope.organizationId(),scope.teamId(),scope.projectId(),serviceAccountId,"service-account-"+serviceAccountId,keyPrefix,keyHash,now());
    }
    public java.util.Optional<Long> activeApplicationKey(long serviceAccountId) { return jdbc.query("SELECT id FROM virtual_api_key WHERE service_account_id=? AND credential_type='APPLICATION' AND enabled=1 ORDER BY id DESC LIMIT 1",(rs,row)->rs.getLong(1),serviceAccountId).stream().findFirst(); }
    public ProjectServiceAccountItem serviceAccount(long id) { return jdbc.queryForObject("SELECT id,project_id,name,enabled,created_at FROM project_service_account WHERE id=?",(rs,row)->new ProjectServiceAccountItem(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getInt(4)==1,JdbcTime.toOffsetDateTime(rs.getTimestamp(5))),id); }
    public boolean projectAllowsModel(long projectId,String model) { Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM project_model_access WHERE project_id=? AND model_name=?",Integer.class,projectId,model); return n!=null&&n>0; }

    private ProjectItem project(long id) { return jdbc.queryForObject("SELECT id,team_id,name,project_code,enabled,created_at FROM project WHERE id=?",(rs,row)->new ProjectItem(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getInt(5)==1,JdbcTime.toOffsetDateTime(rs.getTimestamp(6))),id); }
    private void requireProject(long team,long project) { Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE id=? AND team_id=? AND enabled=1",Integer.class,project,team); if(n==null||n==0)throw bad("Project was not found or is disabled."); }
    private void requireOwner(long team,long member) { Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM team t JOIN team_member m ON m.id=? AND m.team_id=t.id AND m.user_id=t.owner_user_id AND m.role='OWNER' AND m.enabled=1 WHERE t.id=? AND t.enabled=1",Integer.class,member,team);if(n==null||n==0)throw bad("This operation requires the active team owner."); }
    private long requireTeamApplicationModel(long team,String model) { Long n=jdbc.queryForObject("SELECT id FROM model_entitlement_grant WHERE team_id=? AND member_id IS NULL AND project_id IS NULL AND model_name=? AND pool_type='APPLICATION' AND status='ACTIVE'",Long.class,team,model);if(n==null)throw bad("Model is not granted to the team application pool: "+model); return n; }
    private Team team(long id){return jdbc.queryForObject("SELECT organization_id FROM team WHERE id=? AND enabled=1",(rs,row)->new Team(rs.getLong(1)),id);}
    private void account(String type,long owner){jdbc.update("INSERT IGNORE INTO quota_account(account_type,owner_id,available_tokens,frozen_tokens,consumed_tokens,version,updated_at) VALUES (?,?,0,0,0,0,?)",type,owner,now());}
    private long id(String sql,Object...args){Long value=jdbc.queryForObject(sql,Long.class,args);if(value==null)throw bad("Required account was not found.");return value;}
    private static List<String> models(List<String> values){return values==null?List.of():List.copyOf(new LinkedHashSet<>(values.stream().filter(v->v!=null&&!v.isBlank()).map(String::trim).toList()));}
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException { long value = rs.getLong(column); return rs.wasNull() ? null : value; }
    private static java.sql.Timestamp now(){return JdbcTime.toTimestamp(OffsetDateTime.now());}
    private static ModelGateException bad(String m){return new ModelGateException(ErrorCode.BAD_MODEL_REQUEST,m);}
    private record Team(long organizationId){}
    private record ServiceScope(long organizationId,long teamId,long projectId){}
}
