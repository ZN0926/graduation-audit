package com.audit.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 一条判定结果。违规与合规都要存，合规记录用于算合规率。
 */
@Data
@Entity
@Table(name = "finding", indexes = {
        @Index(name = "idx_finding_task", columnList = "task_id")
})
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "rule_id", length = 64)
    private String ruleId;

    @Column(name = "file_path", length = 512)
    private String filePath;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "code_snippet", columnDefinition = "TEXT")
    private String codeSnippet;

    @Column(nullable = false)
    private Boolean violated = false;

    @Column(length = 16)
    private String severity;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "fix_suggestion", columnDefinition = "TEXT")
    private String fixSuggestion;

    private Double confidence;
}