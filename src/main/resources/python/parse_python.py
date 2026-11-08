# -*- coding: utf-8 -*-
"""Python 代码切片器，由 CodeParseService 通过子进程调用。

约定：入参 argv[1] 是源码路径；stdout 输出 JSON 数组，每项形如
{"nodeName": "foo", "kind": "FunctionDef", "startLine": 3, "endLine": 12}

TODO(M3)：用 ast 解析，遍历 FunctionDef / AsyncFunctionDef / ClassDef。
"""
import sys


def main():
    # TODO(M3)
    raise NotImplementedError("M3 待实现")


if __name__ == "__main__":
    main()
