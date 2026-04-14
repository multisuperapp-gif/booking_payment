package com.msa.booking.payment.persistence.repository;

import com.msa.booking.payment.persistence.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
}
