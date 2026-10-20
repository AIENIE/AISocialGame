-- Existing seeds and FULL sessions survive; historical recovery material retires once.
CREATE TABLE IF NOT EXISTS admin_auth_subject_locks (
 subject_id VARCHAR(128) PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS admin_emergency_codes (
 record_id CHAR(36) PRIMARY KEY,
 subject_id VARCHAR(128) NOT NULL,
 code_hash VARCHAR(255) NOT NULL,
 ciphertext TEXT NOT NULL,
 nonce VARBINARY(32) NOT NULL,
 key_version VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 used_at DATETIME(6) NULL,
 KEY idx_admin_emergency_subject(subject_id,used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS admin_emergency_challenge_versions (
 challenge_hash CHAR(64) PRIMARY KEY,
 credential_version BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS admin_emergency_upgrade (
 version_id INT PRIMARY KEY,
 completed_at DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO admin_emergency_upgrade(version_id) VALUES(1);
UPDATE admin_recovery_codes SET replaced_at=COALESCE(replaced_at,CURRENT_TIMESTAMP(6))
 WHERE EXISTS (SELECT 1 FROM admin_emergency_upgrade WHERE version_id=1 AND completed_at IS NULL);
UPDATE admin_sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(6))
 WHERE scope='RECOVERY_REBIND_ONLY' AND EXISTS (SELECT 1 FROM admin_emergency_upgrade WHERE version_id=1 AND completed_at IS NULL);
UPDATE admin_auth_challenges SET consumed_at=COALESCE(consumed_at,CURRENT_TIMESTAMP(6)),encrypted_secret=NULL,nonce=NULL,key_version=NULL
 WHERE purpose='REBIND' AND EXISTS (SELECT 1 FROM admin_emergency_upgrade WHERE version_id=1 AND completed_at IS NULL);
UPDATE admin_emergency_upgrade SET completed_at=CURRENT_TIMESTAMP(6) WHERE version_id=1 AND completed_at IS NULL;
