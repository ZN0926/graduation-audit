package com.audit.service;

import com.audit.model.DocChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规范文档切块器：把一本书/一份规范，切成「一条条款一个块」。
 *
 * <p>输入：一个已经抽成纯文本的文件（`.txt`，UTF-8）。PDF 先用 ExtractPdf 转成 txt。
 * <p>输出：{@link DocChunk} 列表，每块带章节路径、条款号、强度（强制/推荐/参考）和行号。
 *
 * <p>为什么要切块：整本书塞给模型会超出上下文，而且模型会"概括"而不是"逐条抽取"，
 * 抽出来的东西无法回溯到原文。切到条款级之后，每条规则都能绑回「第几条第几款」，
 * 论文里可以逐条查证，答辩被问"这条规则哪来的"时能立刻翻到。
 *
 * <p>识别规则（阿里手册格式）：
 * <pre>
 * 一、编程规约            ← 一级标题
 * (一) 命名风格           ← 二级标题
 * 1.【强制】……            ← 一条条款开始
 * ```
 * 说明：……
 * ```
 * 正例：…… / 反例：……     ← 归属上一条条款
 * </pre>
 *
 * <p>若文档里一条条款都识别不到，退化成「按空行分段、每段上限 1200 字」的兜底模式，
 * 保证任何纯文本都能切出东西来（质量差一些，但不会卡死）。
 */
@Slf4j
@Service
public class DocChunker {

    /** 单块正文上限，超过就截断（提示词里够用了，太长只会烧 token）。 */
    private static final int MAX_CHUNK_CHARS = 1500;

    /** 兜底模式的分段上限。 */
    private static final int FALLBACK_CHUNK_CHARS = 1200;

    /** 条款起始行：`1.【强制】……`（全角括号）或 `1.【强制】`后跟空格。 */
    private static final Pattern CLAUSE = Pattern.compile("^\\s*(\\d+)\\s*[.．、]\\s*【(强制|推荐|参考)】\\s*(.*)$");

    /** 一级标题：`一、编程规约`。 */
    private static final Pattern HEADING1 = Pattern.compile("^\\s*([一二三四五六七八九十]+)\\s*、\\s*(\\S.{0,30})$");

    /** 二级标题：`(一) 命名风格`。 */
    private static final Pattern HEADING2 = Pattern.compile("^\\s*[\\(（]\\s*([一二三四五六七八九十]+)\\s*[\\)）]\\s*(\\S.{0,30})$");

    /**
     * 切块主入口。
     *
     * @param file 纯文本文件路径
     * @return 条款级块列表；文件读不到会抛 IOException
     */
    public List<DocChunk> chunk(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = raw.split("\r?\n", -1);
        String docName = file.getFileName().toString();

        List<DocChunk> chunks = new ArrayList<>();
        String heading1 = "";
        String heading2 = "";

        DocChunk current = null;
        StringBuilder body = new StringBuilder();
        int currentEnd = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            Matcher m1 = HEADING1.matcher(line);
            if (m1.matches() && !trimmed.contains("....")) {
                heading1 = trimmed;
                heading2 = "";
                continue;
            }
            Matcher m2 = HEADING2.matcher(line);
            if (m2.matches()) {
                heading2 = trimmed;
                continue;
            }

            Matcher mc = CLAUSE.matcher(line);
            if (mc.matches()) {
                // 遇到新条款：先把上一条收尾
                if (current != null) {
                    flush(chunks, current, body, currentEnd);
                }
                current = new DocChunk();
                current.setDocPath(docName);
                current.setSection(join(heading1, heading2));
                current.setClauseNo(mc.group(1));
                current.setLevel(mc.group(2));
                current.setStartLine(i + 1);
                currentEnd = i + 1;
                body = new StringBuilder(mc.group(3).trim());
                continue;
            }

            // 普通行：归到当前条款（直到遇到下一个条款/标题）
            if (current != null && !trimmed.isEmpty()) {
                currentEnd = i + 1;
                if (body.length() < MAX_CHUNK_CHARS) {
                    body.append('\n').append(trimmed);
                }
            }
        }
        if (current != null) {
            flush(chunks, current, body, currentEnd);
        }

        if (chunks.isEmpty()) {
            log.warn("未识别到任何【强制/推荐/参考】条款，退化为按段落切分：{}", file);
            chunks = fallbackSplit(lines, docName);
        }
        log.info("文档切块完成：{} 个块，来源 {}", chunks.size(), docName);
        return chunks;
    }

    private static void flush(List<DocChunk> chunks, DocChunk chunk, StringBuilder body, int endLine) {
        String text = body.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_CHUNK_CHARS) {
            text = text.substring(0, MAX_CHUNK_CHARS);
        }
        chunk.setText(text);
        chunk.setEndLine(Math.max(chunk.getStartLine(), endLine));
        chunks.add(chunk);
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + " > " + b;
    }

    /** 兜底：按空行分段，每段超过上限再硬切。 */
    private List<DocChunk> fallbackSplit(String[] lines, String docName) {
        List<DocChunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int start = 0;

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                if (buf.length() > 0) {
                    chunks.add(buildFallback(docName, buf.toString(), start, i));
                    buf.setLength(0);
                }
                continue;
            }
            if (buf.length() == 0) {
                start = i + 1;
            }
            buf.append(t);
            if (buf.length() >= FALLBACK_CHUNK_CHARS) {
                chunks.add(buildFallback(docName, buf.toString(), start, i + 1));
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            chunks.add(buildFallback(docName, buf.toString(), start, lines.length));
        }
        return chunks;
    }

    private static DocChunk buildFallback(String docName, String text, int start, int end) {
        DocChunk c = new DocChunk();
        c.setDocPath(docName);
        c.setSection("（未识别章节）");
        c.setClauseNo("");
        c.setLevel("");
        c.setText(text.length() > FALLBACK_CHUNK_CHARS ? text.substring(0, FALLBACK_CHUNK_CHARS) : text);
        c.setStartLine(start);
        c.setEndLine(end);
        return c;
    }
}
