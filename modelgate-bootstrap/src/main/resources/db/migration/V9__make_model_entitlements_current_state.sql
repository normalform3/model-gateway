-- Entitlement changes are now applied in place. Historical grants are not a
-- user-managed resource, so remove the rows produced by the previous
-- supersede/revoke workflow before enforcing the grant-to-usage relationship.
DELETE usage_row
FROM model_entitlement_usage usage_row
LEFT JOIN model_entitlement_grant grant_row ON grant_row.id = usage_row.grant_id
WHERE grant_row.id IS NULL OR grant_row.status IN ('SUPERSEDED', 'REVOKED');

DELETE FROM model_entitlement_grant
WHERE status IN ('SUPERSEDED', 'REVOKED');

ALTER TABLE model_entitlement_usage
    ADD CONSTRAINT fk_model_entitlement_usage_grant
        FOREIGN KEY (grant_id) REFERENCES model_entitlement_grant(id)
        ON DELETE CASCADE;
