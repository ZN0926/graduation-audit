package com.audit.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 【这是什么】代码切片。把一个源文件切成"模型能一口吃下"的小段，每段都带精确行号。
 * <p>它不是数据库表，只在内存里活一次：解析出来 → 交给 LlmService 判定 → 结果写进 {@link Finding}，
 * 切片本身不落库（落库的是"哪条规则在第几行被违反"）。
 *
 * 【输入 → 输出】
 * <pre>
 * 由 {@link com.audit.service.CodeParseService#parse} 产生，例如 LlmService.java 第 82-120 行：
 *   filePath      = "...\\service\\LlmService.java"
 *   language      = "java"
 *   nodeName      = "chat"              ← 方法名
 *   kind          = "method"            ← method / constructor / class / function / window
 *   startLine     = 82                  ← 1-based，含
 *   endLine       = 120                 ← 1-based，含
 *   code          = "public String chat(...) { ... }"   ← 原文，不加工
 *   categoryHints = ["auth", "log"]     ← 顺带扫到的关键词线索
 * </pre>
 *
 * 【谁用它】块 3 产出；块 5 的 {@code AuditService} 消费（按 categoryHints 挑规则，再逐块问模型）。
 *
 * 【失败边界】行号一旦错位，报告就会指到别的代码——这是本项目最致命的一类 bug。
 * 所以 {@code startLine/endLine} 只有 {@link com.audit.service.CodeParseService} 能填，
 * 任何地方都不要再"估算"行号；{@code code} 必须能按行号在原文件里逐行对上。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunk {

    /** 相对或绝对路径，用于报告展示 */
    private String filePath;

    /** java / python */
    private String language;

    /** 方法名 / 类名 / 函数名；窗口切片沿用所属节点的名字 */
    private String nodeName;

    /** method / constructor / class / function / window（超长方法被切成了窗口） */
    private String kind;

    /** 1-based，含 */
    private Integer startLine;

    /** 1-based，含 */
    private Integer endLine;

    /** 这段的源码原文（含缩进和中文注释，不做任何改写） */
    private String code;

    /** 切分时顺带命中的关键词线索，如 sql、exec、password，供块 5 挑规则用 */
    private List<String> categoryHints;
}
