package com.audit.service;

import com.audit.model.CodeChunk;
import com.audit.util.JsonUtil;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 【这是什么】代码切片器。输入一个源文件，输出一组带"精确行号"的小段，
 * 让模型每次只看几十行，而不是一次吞几千行。
 * <p>为什么必须切：模型上下文有限，而且长文件里真正有问题的往往只有几行——
 * 整篇丢进去，模型会把注意力摊薄，漏报率反而上升。
 *
 * 【输入 → 输出】
 * <pre>
 * parse("D:\\x\\LlmService.java", "java")
 *   → [CodeChunk(nodeName="chat",            kind="method",   startLine=82,  endLine=120),
 *      CodeChunk(nodeName="chatForArray",    kind="method",   startLine=147, endLine=150),
 *      CodeChunk(nodeName="chatForObject",   kind="method",   startLine=152, endLine=155)]
 *
 * parse("D:\\x\\demo.py", "python")
 *   → [CodeChunk(nodeName="login", kind="method", startLine=5, endLine=18), ...]
 *
 * 一个方法超过 80 行时，会被继续切成多块：第一块 kind 保持 "method"，
 * 后续块的 kind 变成 "window"，nodeName 不变。
 * </pre>
 *
 * 【谁用它】块 5 的 {@code AuditService}：上传文件 → 本类切片 → 逐块调模型。
 *
 * 【失败边界】
 * <ul>
 *   <li>Java 语法错误 → JavaParser 抛 {@code ParseProblemException}，直接上抛，由调用方转成 400；</li>
 *   <li>Python 语法错误 / 解释器不存在 → 抛 {@code IllegalStateException}，消息里带 stderr 原文；</li>
 *   <li>行号永远是 1-based 且含端点：第 start 行对应 {@code allLines.get(start - 1)}。
 *       这一条错一次，所有报告都会指错位置，所以本类里只有 {@link #build} 一处做行号减法。</li>
 * </ul>
 *
 * 【工程细节】
 * <ul>
 *   <li>Python 侧不引第三方库，只用标准库 {@code ast}，脚本随 jar 打包在
 *       {@code resources/python/parse_python.py}；运行时释放到临时文件再执行，
 *       这样打成 fat jar 也能用（{@code ClassPathResource#getFile} 在 jar 里会失败）。</li>
 *   <li>解释器默认取 PATH 里的 {@code python}，可用 {@code python.executable} 覆盖；
 *       找不到时自动退到 {@code python3}。</li>
 *   <li>stdout / stderr 必须并发读，否则子进程写满管道缓冲区会卡死。</li>
 * </ul>
 */
@Slf4j
@Service
public class CodeParseService {

    /** 单块超过这个行数就再拆窗口：模型一次别超过 80 行 */
    private static final int MAX_CHUNK_LINES = 80;

    /** 子进程最长等待时间，防止 python 卡死拖住整个请求 */
    private static final long PYTHON_TIMEOUT_SECONDS = 60;

    /** 解释器路径；Windows 上装的是 D:\python\python.exe，已在 PATH 里 */
    @Value("${python.executable:python}")
    private String pythonExecutable = "python";

    /** 释放到临时目录的脚本路径（只释放一次） */
    private volatile Path pythonScriptPath;

    /**
     * 切片的唯一入口。
     *
     * @param filePath 源文件绝对路径
     * @param language {@code java} / {@code python}；传 null 或空则按扩展名猜
     * @return 按源码顺序排列的块；空文件或没有可切节点时返回空列表（不返回 null）
     */
    public List<CodeChunk> parse(String filePath, String language) throws Exception {
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在：" + filePath);
        }
        String lang = (language == null || language.isBlank()) ? detectLanguage(filePath) : language.trim().toLowerCase(Locale.ROOT);

        List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<CodeChunk> chunks = "python".equals(lang)
                ? parsePython(filePath, allLines)
                : parseJava(filePath, allLines);

        log.info("切片完成：{} → {} 行 / {} 块（多块时首块 {}-{}）", path.getFileName(), allLines.size(), chunks.size(),
                chunks.isEmpty() ? 0 : chunks.get(0).getStartLine(),
                chunks.isEmpty() ? 0 : chunks.get(0).getEndLine());
        return chunks;
    }

    /** 只给路径，语言按扩展名判断 */
    public List<CodeChunk> parse(String filePath) throws Exception {
        return parse(filePath, null);
    }

    /** 按扩展名判断语言：.py → python，其余按 java 处理（块 5 上传时会先用它分流） */
    public static String detectLanguage(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".py") ? "python" : "java";
    }

    // ---------- Java ----------

    private List<CodeChunk> parseJava(String filePath, List<String> allLines) throws IOException {
        String source = String.join("\n", allLines);
        CompilationUnit cu = StaticJavaParser.parse(source);
        List<CodeChunk> chunks = new ArrayList<>();

        // 方法优先：方法块比类块信息量大。构造器也要审（初始化里塞危险逻辑很常见）
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            chunks.addAll(toChunks(allLines, filePath, "java", m.getNameAsString(), "method", m));
        }
        for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
            chunks.addAll(toChunks(allLines, filePath, "java", c.getNameAsString(), "constructor", c));
        }
        // 没有方法也没有构造器的类（纯字段 / 常量类）也要留一块，否则整个文件被跳过
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (c.getMethods().isEmpty() && c.getConstructors().isEmpty()) {
                chunks.addAll(toChunks(allLines, filePath, "java", c.getNameAsString(), "class", c));
            }
        }
        chunks.sort((a, b) -> Integer.compare(a.getStartLine(), b.getStartLine()));
        return chunks;
    }

    private List<CodeChunk> toChunks(List<String> allLines, String filePath, String language,
                                     String nodeName, String kind, Node node) {
        int begin = node.getRange().map(r -> r.begin.line).orElse(0);
        int end = node.getRange().map(r -> r.end.line).orElse(0);
        if (begin <= 0 || end < begin) {
            log.warn("节点 {} 没有有效范围，跳过", nodeName);
            return List.of();
        }
        return window(allLines, filePath, language, nodeName, kind, begin, end);
    }

    /** 把 [begin, end] 按 MAX_CHUNK_LINES 切成若干窗口 */
    private List<CodeChunk> window(List<String> allLines, String filePath, String language,
                                   String nodeName, String kind, int begin, int end) {
        List<CodeChunk> result = new ArrayList<>();
        int cursor = begin;
        while (cursor <= end) {
            int windowEnd = Math.min(cursor + MAX_CHUNK_LINES - 1, end);
            result.add(build(allLines, filePath, language, nodeName,
                    cursor == begin ? kind : "window", cursor, windowEnd));
            cursor = windowEnd + 1;
        }
        return result;
    }

    // ---------- Python ----------

    private List<CodeChunk> parsePython(String filePath, List<String> allLines) throws Exception {
        Path script = ensurePythonScript();
        ProcessBuilder pb = new ProcessBuilder(pythonExecutable, script.toString(), filePath);
        pb.redirectErrorStream(false);
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            if ("python".equals(pythonExecutable)) {
                log.warn("'python' 启动失败（{}），改用 'python3'", e.getMessage());
                pb = new ProcessBuilder("python3", script.toString(), filePath);
                pb.redirectErrorStream(false);
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                proc = pb.start();
            } else {
                throw new IllegalStateException("无法启动 Python 解释器 " + pythonExecutable
                        + "，请用 python.executable 指定绝对路径", e);
            }
        }

        // stderr 必须并发读，不然管道写满会互相卡死
        Process p = proc;
        CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> readQuietly(p.getErrorStream()));
        String out = readQuietly(p.getInputStream());
        boolean finished = p.waitFor(PYTHON_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("Python 解析超时（>" + PYTHON_TIMEOUT_SECONDS + "s）：" + filePath);
        }
        String err = errFuture.join();
        if (p.exitValue() != 0) {
            throw new IllegalStateException("Python 解析失败（exit=" + p.exitValue() + "）：" + err.trim());
        }

        JsonNode nodes = JsonUtil.MAPPER.readTree(out.isBlank() ? "[]" : out);
        if (!nodes.isArray()) {
            throw new IllegalStateException("Python 输出不是 JSON 数组：" + out);
        }

        List<CodeChunk> chunks = new ArrayList<>();
        for (JsonNode n : nodes) {
            int begin = n.path("startLine").asInt(0);
            int end = n.path("endLine").asInt(begin);
            String name = n.path("nodeName").asString("");
            String kind = n.path("kind").asString("function");
            if (begin <= 0) {
                continue;
            }
            chunks.addAll(window(allLines, filePath, "python", name, kind, begin, Math.max(end, begin)));
        }
        return chunks;
    }

    /** 把打包在 resources 里的脚本释放到临时目录（jar 内不能用 getFile） */
    private Path ensurePythonScript() throws IOException {
        Path cached = pythonScriptPath;
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }
        synchronized (this) {
            if (pythonScriptPath != null && Files.isRegularFile(pythonScriptPath)) {
                return pythonScriptPath;
            }
            ClassPathResource resource = new ClassPathResource("python/parse_python.py");
            try (InputStream in = resource.getInputStream()) {
                Path tmp = Files.createTempFile("parse_python_", ".py");
                Files.write(tmp, in.readAllBytes());
                tmp.toFile().deleteOnExit();
                pythonScriptPath = tmp;
                log.debug("Python 切片脚本已释放到 {}", tmp);
                return tmp;
            }
        }
    }

    private static String readQuietly(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ---------- 公共 ----------

    /**
     * 唯一做行号换算的地方：{@code allLines} 是 0-based，行号是 1-based，
     * 所以第 start 行是 {@code allLines.get(start - 1)}；end 含端点，subList 需要 +1 语义。
     */
    private CodeChunk build(List<String> allLines, String filePath, String language,
                            String nodeName, String kind, int start, int end) {
        int safeStart = Math.max(1, start);
        int safeEnd = Math.min(end, allLines.size());
        if (safeEnd < safeStart) {
            safeEnd = safeStart;
        }
        String code = String.join("\n", allLines.subList(safeStart - 1, safeEnd));
        return new CodeChunk(filePath, language, nodeName, kind, safeStart, safeEnd,
                code, detectHints(code));
    }

    /** 顺带扫关键词：块 5 用它先挑一批相关规则，能把模型调用量砍掉一半左右 */
    private List<String> detectHints(String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        Map<String, String[]> hintMap = new LinkedHashMap<>();
        hintMap.put("sql", new String[]{"select ", "insert ", "update ", "delete ", "executequery", "executeupdate", "createstatement"});
        hintMap.put("exec", new String[]{"runtime.getruntime", ".exec(", "subprocess", "os.system", "eval(", "exec("});
        hintMap.put("crypto", new String[]{"md5", "sha1", "des", "ecb", "math.random", "random."});
        hintMap.put("path", new String[]{"../", "new file(", "path.of", "paths.get", "open("});
        hintMap.put("log", new String[]{"log.info", "log.debug", "print(", "system.out"});
        hintMap.put("auth", new String[]{"session", "cookie", "jwt", "password", "token", "getparameter"});

        return hintMap.entrySet().stream()
                .filter(e -> Arrays.stream(e.getValue()).anyMatch(lower::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
