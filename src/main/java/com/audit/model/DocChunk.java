package com.audit.model;

import lombok.Data;

/**
 * 从规范文档里切出来的一个语义单元（通常是一条条款）。
 *
 * <p>与 {@link CodeChunk} 的区别：CodeChunk 是被审计的**代码**，DocChunk 是被抽取规则的**文档**。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code docPath} —— 来源文件，写进 Rule.sourceDoc 供追溯</li>
 *   <li>{@code section} —— 所属章节路径，如「一、编程规约 &gt; (一) 命名风格」，抽规则时当上下文</li>
 *   <li>{@code clauseNo} —— 条款号（如 1、2、3），写进 Rule.sourceRef</li>
 *   <li>{@code level} —— 强制 / 推荐 / 参考</li>
 *   <li>{@code text} —— 条款正文（含说明、正例、反例）</li>
 *   <li>{@code startLine / endLine} —— 在原文中的行号（1-based 含端点），出问题时能直接翻原文核对</li>
 * </ul>
 */
@Data
public class DocChunk {

    private String docPath;
    private String section;
    private String clauseNo;
    private String level;
    private String text;
    private int startLine;
    private int endLine;

    /** 给提示词用的紧凑表示：章节 + 强度 + 正文。 */
    public String toPromptBlock() {
        return "【章节】" + section + "\n【强度】" + level + "\n【条款】" + text;
    }
}
