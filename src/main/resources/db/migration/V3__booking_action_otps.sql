CREATE TABLE IF NOT EXISTS booking_action_otps (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  booking_id BIGINT UNSIGNED NOT NULL,
  otp_purpose ENUM('START_WORK','COMPLETE_WORK','MUTUAL_CANCEL') NOT NULL,
  otp_code VARCHAR(6) NOT NULL,
  issued_to_user_id BIGINT UNSIGNED NOT NULL,
  otp_status ENUM('GENERATED','USED','EXPIRED','CANCELLED') NOT NULL DEFAULT 'GENERATED',
  expires_at DATETIME NOT NULL,
  used_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_booking_action_otps_booking_purpose_status (booking_id, otp_purpose, otp_status),
  CONSTRAINT fk_booking_action_otps_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_booking_action_otps_user FOREIGN KEY (issued_to_user_id) REFERENCES users(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
