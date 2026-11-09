package com.audit.util;

/**
 * 规则"跨语言归一键"的计算。
 *
 * <p>目的：把"同一类漏洞"在不同语言下的规则串成一组，做三种用途：
 * <ul>
 *   <li>报告里能写"SQL 注入：Java 5 条 / Python 3 条 / C 2 条"；</li>
 *   <li>加新语言时不用改表，只要新规则的 canonical_key 落在同一个键上；</li>
 *   <li>评测时按漏洞类型做对照，而不是按语言各算各的。</li>
 * </ul>
 *
 * <p>取值口径：能归到具体 CWE 的，直接用它（{@code CWE-89}）；
 * 归不到具体 CWE 的（{@code CWE-000}、空值），退化为分类键（{@code CAT-injection}）。
 * 这样既不会把"没有 CWE 的规则"丢掉，也不会让它们混进安全项统计。
 */
public final class RuleKeys {

    private RuleKeys() {
    }

    public static String canonicalKey(String cwe, String category) {
        String c = cwe == null ? "" : cwe.trim().toUpperCase();
        if (c.matches("CWE-\\d{3}") && !"CWE-000".equals(c)) {
            return c;
        }
        String cat = category == null ? "" : category.trim().toLowerCase();
        return cat.isEmpty() ? "CAT-unknown" : "CAT-" + cat;
    }
}
