package com.audit.repository;

import com.audit.model.AuditTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * AuditTask 的持久层。
 */
public interface AuditTaskRepository extends JpaRepository<AuditTask, Long> {
    List<AuditTask> findAllByOrderByIdDesc();
}