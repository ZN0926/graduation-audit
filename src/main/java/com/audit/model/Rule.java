package com.audit.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计规则。由规则蒸馏生成，是 LLM 判定的唯一依据。
 */
@Data
@Entity
@Table(name = "rule", indexes = {
        @Index(name = "idx_rule_language", columnList = "language"),
        @Index(name = "idx_rule_category", columnList = "category")
})
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false, unique = true, length = 64)
    private String ruleId;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(length = 64)
    private String category;

    @Column(length = 32)
    private String cwe;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "positive_example", columnDefinition = "TEXT")
    private String positiveExample;

    @Column(name = "negative_example", columnDefinition = "TEXT")
    private String negativeExample;

    @Column(length = 16)
    private String severity;

    @Column(name = "detection_hint", columnDefinition = "TEXT")
    private String detectionHint;

    @Column(name = "evidence_quote", length = 200)
    private String evidenceQuote;

    /** 规则来源文档：alibaba-p3c / pylint / bandit …（B 通道直转时也能追溯出处） */
    @Column(name = "source_doc", length = 64)
    private String sourceDoc;

    /** 来源内部编号：条款号（如 B101、IDS00-J）或消息号（E0601） */
    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    /** 可检测方式：pattern / dataflow / semantic / manual，manual 不进自动检测集 */
    @Column(length = 16)
    private String detectability;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}