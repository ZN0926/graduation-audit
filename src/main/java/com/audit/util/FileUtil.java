package com.audit.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件工具：判断语言、落盘保存上传文件、按 UTF-8 读源码。
 *
 * <p>为什么单独一个类：上传接口只该做"收字节 → 交给人管路径"，路径拼接、
 * 文件名消毒、编码读取这些容易出安全问题的细节集中在这里，出问题只查一处。
 *
 * <p>失败边界：扩展名不认识抛 {@link IllegalArgumentException}（接口层转 400）；
 * 读写磁盘失败抛 {@link IOException}（接口层转 500）。
 */
public final class FileUtil {

    private FileUtil() {
    }

    /** 按扩展名判断语言。目前只支持 java / python，加 C 时在这里补分支。 */
    public static String detectLanguage(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".py")) {
            return "python";
        }
        throw new IllegalArgumentException("只支持 .java 和 .py 文件，收到：" + fileName);
    }

    /**
     * 把上传内容存到指定目录，返回落盘路径。
     *
     * <p>文件名加时间戳前缀，避免同名覆盖；非法字符（含路径分隔符）替换成下划线，
     * 防止 {@code ../../etc/passwd} 这类文件名穿越目录。
     */
    public static Path save(Path dir, String fileName, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        String raw = (fileName == null || fileName.isBlank()) ? "upload.bin" : fileName;
        String safe = raw.replaceAll("[\\\\/:*?\"<>|]", "_");
        String stamped = System.currentTimeMillis() + "_" + safe;
        Path target = dir.resolve(stamped).normalize();
        if (!target.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("非法文件名：" + fileName);
        }
        Files.write(target, bytes);
        return target;
    }

    /** 按 UTF-8 读整个文件（源码基本都是 UTF-8；读坏了会在解析阶段报错，比静默乱码好）。 */
    public static String readUtf8(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
