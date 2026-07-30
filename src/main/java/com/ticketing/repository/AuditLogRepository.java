package com.ticketing.repository;

import com.ticketing.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository interface for managing AuditLog entities.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
