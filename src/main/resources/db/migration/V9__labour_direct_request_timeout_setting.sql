INSERT INTO app_settings (setting_key, setting_value, description, updated_at)
VALUES ('labour.direct.request.timeout.seconds', '45', 'Seconds given to a directly selected labour to accept or reject a booking request.', NOW())
ON DUPLICATE KEY UPDATE
  setting_value = VALUES(setting_value),
  description = VALUES(description);
