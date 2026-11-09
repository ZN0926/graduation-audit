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
        @Index(name = "idx_rule_category", columnList = "category"),
        @Index(name = "idx_rule_canonical", columnList = "canonical_key")
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

    /**
     * 跨语言"同一类漏洞"的归一键，用于把 Java / Python / C 的同类规则串起来做对照。
     *
     * <p>取值规则：能归到具体 CWE 的用 CWE 号（如 {@code CWE-89}）；
     * 归不到具体 CWE 的（{@code CWE-000}）用 {@code CAT-<category>}（如 {@code CAT-error-handling}）。
     * 加一门新语言时，只要它的同类规则也落在这个键上，{@code GROUP BY canonical_key} 就能直接列出
     * "一种漏洞、三种语言实现"，不需要跨表 join。
     */
    @Column(name = "canonical_key", length = 64)
    private String canonicalKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}