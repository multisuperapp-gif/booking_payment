ALTER TABLE push_notification_tokens
  ADD COLUMN app_context VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN' AFTER push_provider,
  ADD KEY idx_push_notification_tokens_user_app_active (user_id, app_context, is_active);
