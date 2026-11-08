package com.audit.repository;

import com.audit.model.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Report 的持久层。
 */
public interface ReportRepository extends JpaRepository<Report, Long> {
    Optional<Report> findByTaskId(Long taskId);
}