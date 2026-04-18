INSERT INTO app_settings (setting_key, setting_value, description, updated_at)
VALUES ('platform.fee.labour', '5.00', 'Labour booking charge percentage collected by platform as commission.', NOW())
ON DUPLICATE KEY UPDATE
  description = VALUES(description);
