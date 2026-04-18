ALTER TABLE booking_requests
  ADD COLUMN requested_provider_count INT NOT NULL DEFAULT 1 AFTER search_longitude;
