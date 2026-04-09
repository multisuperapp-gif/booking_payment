SET @booking_request_id_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'bookings'
    AND COLUMN_NAME = 'booking_request_id'
);

SET @booking_request_id_add_column_sql = IF(
  @booking_request_id_column_exists = 0,
  'ALTER TABLE bookings ADD COLUMN booking_request_id BIGINT UNSIGNED NULL AFTER id',
  'SELECT 1'
);
PREPARE booking_request_id_add_column_stmt FROM @booking_request_id_add_column_sql;
EXECUTE booking_request_id_add_column_stmt;
DEALLOCATE PREPARE booking_request_id_add_column_stmt;

SET @booking_request_id_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'bookings'
    AND INDEX_NAME = 'idx_bookings_booking_request_id'
);

SET @booking_request_id_add_index_sql = IF(
  @booking_request_id_index_exists = 0,
  'ALTER TABLE bookings ADD KEY idx_bookings_booking_request_id (booking_request_id)',
  'SELECT 1'
);
PREPARE booking_request_id_add_index_stmt FROM @booking_request_id_add_index_sql;
EXECUTE booking_request_id_add_index_stmt;
DEALLOCATE PREPARE booking_request_id_add_index_stmt;
