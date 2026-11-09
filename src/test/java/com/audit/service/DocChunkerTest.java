package com.audit.service;

import com.audit.model.DocChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DocChunker 的本地验证：不需要模型、不需要数据库、不花钱。
 *
 * <p>核心断言是"切出来的块能对回原文"——块的行号范围必须覆盖它自己的正文，
 * 且条款号/强度/章节不能为空。这条一旦成立，后面 evidence_quote 回查才有意义
 * （回查的参照物就是块的正文）。
 */
class DocChunkerTest {

    private final DocChunker chunker = new DocChunker();

    /** 真实阿里手册：290 条条款应被切出来，且标题/强度/行号齐全。 */
    @Test
    void alibabaManualSplitsIntoClauses() throws IOException {
        Path doc = Path.of("docs-source/java/alibaba-p3c.txt");
        assumeTrue(Files.exists(doc), "docs-source/java/alibaba-p3c.txt 不存在（语料不随仓库分发）");

        List<DocChunk> chunks = chunker.chunk(doc);

        // 手册自述：【强制】193 条 + 【推荐】97 条
        assertTrue(chunks.size() >= 280, "条款数偏少，实际 " + chunks.size());
        assertTrue(chunks.size() <= 320, "条款数偏多，疑似把目录/说明也切进来了，实际 " + chunks.size());

        long withSection = chunks.stream().filter(c -> c.getSection() != null && !c.getSection().isBlank()).count();
        assertEquals(chunks.size(), withSection, "有块没继承到章节");

        for (DocChunk c : chunks) {
            assertFalse(c.getText().isBlank(), "块正文为空，条款 " + c.getClauseNo());
            assertTrue(c.getStartLine() > 0, "startLine 必须从 1 开始");
            assertTrue(c.getEndLine() >= c.getStartLine(), "endLine 不能小于 startLine");
            assertTrue(c.getText().length() <= 1500, "单块超过上限");
        }

        DocChunk first = chunks.get(0);
        assertEquals("1", first.getClauseNo(), "第一条应从条款 1 开始");
        assertEquals("强制", first.getLevel());
        assertTrue(first.getSection().contains("命名风格"), "章节路径应含（一）命名风格，实际 " + first.getSection());
        assertTrue(first.getText().contains("下划线"), "条款 1 正文应含「下划线」");
    }

    /** 条款正文要带上后面的「说明/正例/反例」，否则模型看不到示例。 */
    @Test
    void clauseBodyIncludesExplanationAndExamples() throws IOException {
        Path doc = Path.of("docs-source/java/alibaba-p3c.txt");
        assumeTrue(Files.exists(doc), "语料不存在");

        List<DocChunk> chunks = chunker.chunk(doc);
        DocChunk withExample = chunks.stream()
                .filter(c -> c.getText().contains("正例") && c.getText().contains("反例"))
                .findFirst()
                .orElse(null);
        assertTrue(withExample != null, "至少应有一条条款把正例/反例并进正文");
    }

    /** 不是规范格式的文本要走兜底切分，不能抛异常、不能返回空。 */
    @Test
    void unknownFormatFallsBackToParagraphs(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("plain.txt");
        Files.writeString(doc, """
                第一段：这是一段没有任何编号的普通说明文字。
                它应该被兜底逻辑切成一段。

                第二段：再来一段。

                第三段。
                """, StandardCharsets.UTF_8);

        List<DocChunk> chunks = chunker.chunk(doc);
        assertFalse(chunks.isEmpty(), "兜底模式不能返回空列表");
        assertTrue(chunks.stream().allMatch(c -> !c.getText().isBlank()));
    }
}
