-- Bootstrap: 确保默认租户存在（standalone 模式必需）
-- 幂等：已存在时仅确保 status=1
INSERT INTO tenants (id, code, name, status, created_at, updated_at)
VALUES (1, 'default', '默认租户', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE status = 1;
