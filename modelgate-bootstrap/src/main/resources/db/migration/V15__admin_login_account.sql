-- Keep the legacy seed row but expose the platform administrator through the concise console account.
UPDATE IGNORE platform_user
SET email = 'admin', updated_at = NOW()
WHERE platform_admin = 1
  AND email = 'admin@modelgate.local';
