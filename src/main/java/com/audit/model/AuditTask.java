package com.audit.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次审计任务。记录文件、语言、进度与状态。
 */
@Data
@Entity
@Table(name = "audit_task")
public class AuditTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(length = 16)
    private String language;

    /** PENDING / RUNNING / DONE / FAILED */
    @Column(length = 16)
    private String status = "PENDING";

    @Column(name = "rule_version", length = 32)
    private String ruleVersion;

    @Column(name = "total_chunks")
    private Integer totalChunks = 0;

    @Column(name = "done_chunks")
    private Integer doneChunks = 0;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}