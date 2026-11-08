package com.audit.service;

import com.audit.model.CodeChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 【这是什么】块 3（代码切片）的自动化验收。不用起服务、不用连数据库、不用网络，
 * 在 IDEA 里点方法左边的绿色三角就能跑。
 *
 * 【它验的是什么】不是"能不能切出块"，而是"**切出来的每一块，首行和末行都能在原文件里逐字对上**"。
 * 行号错一行，块 5 的审计报告就会指到别的代码，所以这是必须钉死的一条。
 *
 * 【三条用例对应三件事】
 * <pre>
 * 1. javaChunksAlignWithSource   ── 真实工程文件（LlmService.java）切出来的每块，首行 == 源文件第 startLine 行
 * 2. pythonChunksAlignWithSource ── 一个含装饰器、嵌套函数、中文的 .py，方法/函数分类正确，行号对齐
 * 3. longMethodIsWindowed        ── 200 行的大方法被切成 method + window + window，每块 ≤80 行
 * </pre>
 *
 * 【谁用它】你（验收块 3）、以后回归测试（改了解析逻辑，跑一遍就知道有没有把行号搞坏）。
 *
 * 【失败边界】Python 用例依赖 PATH 里的 python；没有解释器时用 {@code assumeTrue} 跳过而不是报红，
 * 免得在没有 Python 的机器上"假失败"。
 */
class CodeParseServiceTest {

    /** 被测对象：字段有默认值（python 解释器 = python），不需要 Spring 容器 */
    private final CodeParseService service = new CodeParseService();

    @Test
    void javaChunksAlignWithSource() throws Exception {
        Path file = Path.of("src/main/java/com/audit/service/LlmService.java");
        assumeTrue(Files.isRegularFile(file), "找不到 LlmService.java，请在工程根目录下运行");

        List<CodeChunk> chunks = service.parse(file.toString(), "java");

        assertFalse(chunks.isEmpty(), "LlmService 至少应切出 chat / chatForArray / chatForObject 三块");
        assertTrue(chunks.size() >= 3, "块数应 ≥3，实际 " + chunks.size());

        for (CodeChunk chunk : chunks) {
            assertChunkAlignedWithSource(file, chunk);
        }

        // 第一块必须是 chat，且首行是它的签名
        CodeChunk first = chunks.get(0);
        assertEquals("chat", first.getNodeName());
        assertTrue(first.getCode().split("\n", -1)[0].trim().startsWith("public String chat("),
                "首块第一行应是方法签名，实际是：" + first.getCode().split("\n")[0].trim());
    }

    @Test
    void pythonChunksAlignWithSource(@TempDir Path dir) throws Exception {
        assumeTrue(pythonAvailable(), "PATH 里没有 python，跳过 Python 用例");

        String source = """
                # -*- coding: utf-8 -*-
                import os


                def run(user_input):
                    os.system("echo " + user_input)


                def outer():

                    def inner(x):
                        return eval(x)

                    return inner


                class UserService:

                    def __init__(self, name):
                        self.name = name

                    @staticmethod
                    def login(username, password):
                        \"\"\"中文注释：验证编码\"\"\"
                        return password == "123456"
                """;
        Path file = dir.resolve("demo.py");
        Files.writeString(file, source, StandardCharsets.UTF_8);

        List<CodeChunk> chunks = service.parse(file.toString(), "python");

        assertFalse(chunks.isEmpty());
        for (CodeChunk chunk : chunks) {
            assertChunkAlignedWithSource(file, chunk);
        }

        CodeChunk login = chunks.stream()
                .filter(c -> "login".equals(c.getNodeName()))
                .findFirst()
                .orElseThrow();
        assertEquals("method", login.getKind(), "类体内的函数应判为 method");
        // 第 22 行是 @staticmethod（装饰器），行号要含装饰器
        assertTrue(login.getCode().split("\n", -1)[0].trim().startsWith("@staticmethod"),
                "带装饰器时首行应为装饰器行，实际是：" + login.getCode().split("\n")[0].trim());
        assertTrue(login.getCode().contains("中文注释"), "中文注释应原样保留");

        assertTrue(chunks.stream().anyMatch(c -> "function".equals(c.getKind()) && "run".equals(c.getNodeName())),
                "顶层函数应判为 function");
    }

    @Test
    void longMethodIsWindowed(@TempDir Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("package demo;\n\npublic class Big {\n    public void huge() {\n");
        for (int i = 0; i < 200; i++) {
            sb.append("        int v").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    }\n}\n");

        Path file = dir.resolve("Big.java");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        List<CodeChunk> chunks = service.parse(file.toString(), "java");

        assertEquals(3, chunks.size(), "200 行的方法应切成 3 块，实际 " + chunks.size());
        assertEquals("method", chunks.get(0).getKind());
        assertEquals("window", chunks.get(1).getKind());
        assertEquals("window", chunks.get(2).getKind());

        for (CodeChunk chunk : chunks) {
            assertTrue(chunk.getEndLine() - chunk.getStartLine() + 1 <= 80,
                    "单块不能超过 80 行：" + chunk.getStartLine() + "-" + chunk.getEndLine());
            assertChunkAlignedWithSource(file, chunk);
        }
    }

    // ---------- 公共断言 ----------

    /**
     * 核心断言：块的 code 首行 == 源文件第 startLine 行；末行 == 第 endLine 行；行数 == end-start+1。
     */
    private void assertChunkAlignedWithSource(Path file, CodeChunk chunk) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] codeLines = chunk.getCode().split("\n", -1);

        assertEquals(chunk.getEndLine() - chunk.getStartLine() + 1, codeLines.length,
                "行数不等于 end-start+1：" + chunk.getNodeName());

        String expectedFirst = lines.get(chunk.getStartLine() - 1);
        assertEquals(expectedFirst, codeLines[0],
                "块首行与源码第 " + chunk.getStartLine() + " 行不一致：" + chunk.getNodeName());

        String expectedLast = lines.get(chunk.getEndLine() - 1);
        assertEquals(expectedLast, codeLines[codeLines.length - 1],
                "块末行与源码第 " + chunk.getEndLine() + " 行不一致：" + chunk.getNodeName());
    }

    private boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
