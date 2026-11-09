package com.audit.prompt;

/**
 * 三套提示词集中放这里：蒸馏、审计、复核。
 *
 * <p>提示词是系统的核心资产，改了要记进文档。
 *
 * <p>当前已实现：{@link #DISTILL_FROM_TEXT_SYSTEM}（A 通道：规范条款 → 规则）。
 * 审计与复核提示词在块 5 补。
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * A 通道的系统提示词：从规范条款里抽规则。
     *
     * <p>设计要点（每条都对应一个坑）：
     * <ul>
     *   <li>要求"只输出 JSON 数组"——不加这句，模型会写"以下是抽取结果："，解析器直接崩；</li>
     *   <li>要求 {@code evidenceQuote} 逐字摘录——这是防幻觉的硬闸，服务端会拿它回查原文，
     *       对不上就丢；光靠"不要编造"是拦不住的；</li>
     *   <li>要求 {@code detectability}——书里大量条款（如"应遵循最小权限"）静态和 LLM 都判不了，
     *       必须显式标成 manual，否则评测时 F1 会被这些条目拖死；</li>
     *   <li>禁止把多条条款合并成一条——合并后无法回溯到"第几条第几款"。</li>
     * </ul>
     */
    public static final String DISTILL_FROM_TEXT_SYSTEM = """
            你是资深代码安全审计专家，任务是把中文/英文编码规范里的条款，转写成可执行的审计规则。

            输出要求：
            1. 只输出一个 JSON 数组，不要任何解释文字、不要 markdown 代码块围栏。
            2. 数组每个元素是一条规则，字段如下（全部必填，除非注明可选）：
               - title：规则标题，20 字以内，动宾结构，例如「禁止使用下划线开头的标识符」
               - category：分类，只能取 injection / crypto / auth / path-traversal / deserialization / secrets / config / error-handling / logging / naming / style 之一
               - cwe：最贴近的 CWE 编号，形如 CWE-89；确实没有对应项填 CWE-000
               - severity：HIGH / MEDIUM / LOW
               - description：这条规则要求什么、为什么，40-120 字
               - detectionHint：怎样在代码里发现违规，写具体模式（用什么 API、什么写法），30-100 字
               - detectability：代码能否自动判定，只能取
                   pattern（有明确的 API/语法特征，可用正则或 AST 匹配）
                   semantic（要看语义/上下文，需要大模型判断）
                   manual（需要人工评审，静态与大模型都不足以判定）
               - positiveExample：合规示例代码或写法，10-200 字，可选
               - negativeExample：违规示例代码或写法，10-200 字，可选
               - evidenceQuote：从给定条款原文中**逐字复制**的一小段，不超过 50 字，不得改写、不得拼接
            3. evidenceQuote 必须能在原文里原样找到；做不到就不要输出这条规则。
            4. 一条条款通常只产出一条规则；如果条款明显含多个独立要求，最多拆成 3 条，不要更多。
            5. 条款本身不可自动检测（如「应遵循最小权限原则」），也要输出，detectability 填 manual。

            只输出 JSON。
            """;

    /**
     * 拼 A 通道的用户提示词：一条条款 + 上下文。
     *
     * @param language 目标语言（java / python），决定 category 与示例代码的写法
     * @param section  所属章节，例如「一、编程规约 > (一) 命名风格」
     * @param level    条款强度：强制 / 推荐 / 参考
     * @param clauseNo 条款号，例如 1
     * @param text     条款正文（含说明、正例、反例）
     */
    public static String buildDistillUser(String language, String section, String level,
                                          String clauseNo, String text) {
        return """
                目标语言：%s
                规范来源：%s
                条款号：%s
                条款强度：%s

                条款原文（请只依据这段文字抽取，不要引入外部知识）：
                ---
                %s
                ---

                请按系统提示的 JSON Schema 输出规则数组。
                """.formatted(language, section, clauseNo, level, text);
    }
}
