# -*- coding: utf-8 -*-
"""Python 侧的"语法边界探测器"，由 CodeParseService 用子进程调用。

【它干什么】
只回答一个问题：这个 .py 文件里，每个函数 / 类分别占第几行到第几行。
不做切片、不做判断、不识字义——切片是 Java 那边的事，这里只提供坐标。

【输入 → 输出】
    python parse_python.py D:\\code\\demo.py
    stdout: [{"nodeName":"login","kind":"method","startLine":5,"endLine":18},
             {"nodeName":"User","kind":"class","startLine":21,"endLine":30}]
出错时写 stderr 并以非 0 退出，Java 侧会把 stderr 原文拼进异常消息。

【为什么用标准库 ast 而不是正则】
正则数不清"函数体到哪一行结束"；ast 自带 end_lineno（Python 3.8+），
还能天然跳过注释里的假函数、字符串里的假括号。

【行号约定】统一 1-based、含端点；带装饰器时 startLine 取装饰器那一行，
这样报告高亮能从 @app.route 开始，而不是从 def 开始。

【模块级语句为什么也要报】
很多真实漏洞不在函数里，而在模块顶层：
    PASSWORD = "admin123"          # 硬编码凭证
    subprocess.call(user_input, shell=True)   # 顶层执行
只报函数/类的话，这些行永远进不了模型视野（块 5 验收时实测漏掉了顶层密码）。
所以额外把"不属于任何函数/类的顶层语句"按 40 行一组报成 kind="module"，
纯 import 行跳过（没有语义，只会浪费 token）。
"""
import ast
import json
import sys

# 模块级语句每块最多多少行：与 Java 侧 MAX_CHUNK_LINES(80) 取一半，
# 因为顶层语句通常很碎，40 行已经能覆盖一整段初始化逻辑。
MODULE_CHUNK_LINES = 40


def _method_ids(tree):
    """收集"直接写在 class 体内"的函数，用于区分 method 和 function。"""
    ids = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            for child in node.body:
                if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    ids.add(id(child))
    return ids


def _start_line(node):
    """带装饰器时，从第一个装饰器算起。"""
    decorators = getattr(node, "decorator_list", None) or []
    lines = [node.lineno] + [d.lineno for d in decorators]
    return min(lines)


def _end_line(node):
    end = getattr(node, "end_lineno", None)
    return end if end else node.lineno


def collect_module_level(tree):
    """把"不属于任何函数/类"的顶层语句，按行号聚成若干块。

    为什么不是一条语句一块：顶层语句常常是
        cfg = load()
        if cfg.debug:
            print(cfg)
    拆成三块会让模型看不到上下文，而 40 行一组刚好能装下一整段初始化逻辑。

    为什么不收 import：import 行几乎不可能违规，收了只会白占 token。
    """
    stmts = []
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef,
                             ast.Import, ast.ImportFrom)):
            continue
        stmts.append(node)
    if not stmts:
        return []

    chunks = []
    start = _start_line(stmts[0])
    end = _end_line(stmts[0])
    head = start
    for node in stmts[1:]:
        n_start, n_end = _start_line(node), _end_line(node)
        # 超过阈值就结块；用 n_end - start 判断，保证块不会因为一条长语句超太多
        if n_end - start + 1 > MODULE_CHUNK_LINES:
            chunks.append({"nodeName": "模块级语句", "kind": "module",
                           "startLine": start, "endLine": end})
            start, head = n_start, n_start
        end = max(end, n_end)
    chunks.append({"nodeName": "模块级语句", "kind": "module",
                   "startLine": start, "endLine": end})
    return chunks


def collect(tree):
    method_ids = _method_ids(tree)
    result = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            kind = "method" if id(node) in method_ids else "function"
        elif isinstance(node, ast.ClassDef):
            kind = "class"
        else:
            continue
        result.append({
            "nodeName": node.name,
            "kind": kind,
            "startLine": _start_line(node),
            "endLine": _end_line(node),
        })
    result.extend(collect_module_level(tree))
    result.sort(key=lambda item: (item["startLine"], item["endLine"]))
    return result


def main():
    if len(sys.argv) < 2:
        sys.stderr.write("usage: parse_python.py <file.py>\n")
        sys.exit(2)

    path = sys.argv[1]
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            source = handle.read()
        tree = ast.parse(source, filename=path)
    except SyntaxError as exc:
        sys.stderr.write("SyntaxError: {} (line {})\n".format(exc.msg, exc.lineno))
        sys.exit(1)
    except OSError as exc:
        sys.stderr.write("OSError: {}\n".format(exc))
        sys.exit(1)

    sys.stdout.write(json.dumps(collect(tree), ensure_ascii=False))


if __name__ == "__main__":
    main()
