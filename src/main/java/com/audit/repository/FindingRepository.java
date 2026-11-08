package com.audit.repository;

import com.audit.model.Finding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Finding 的持久层。
 */
public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByTaskId(Long taskId);
    List<Finding> findByTaskIdAndViolated(Long taskId, Boolean violated);
    long countByTaskIdAndViolated(Long taskId, Boolean violated);
}