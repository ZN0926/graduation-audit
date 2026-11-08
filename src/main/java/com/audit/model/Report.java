package com.audit.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计报告。含合规率与 Markdown 正文。
 */
@Data
@Entity
@Table(name = "report")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "summary_text", columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "compliance_rate")
    private Double complianceRate;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String markdown;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String html;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}