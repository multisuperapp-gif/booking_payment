package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.UserSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, Long> {
    Optional<UserSessionEntity> findByIdAndUserId(Long id, Long userId);
}
