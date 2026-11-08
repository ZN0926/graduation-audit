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
"""
import ast
import json
import sys


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
