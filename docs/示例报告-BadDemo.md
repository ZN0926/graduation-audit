# 代码审计报告

> 本次审计共发现 1 处问题，涉及 1 条规则；其中高危 1 处。规则库共 196 条，合规率 99.5%。最需要先修的是第 12 行（JAVA-SB-SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE）。

## 一、任务信息

| 项 | 值 |
|---|---|
| 文件 | BadDemo.java |
| 语言 | java |
| 任务号 | 1 |
| 规则版本 | rules-196 |
| 代码块数 | 3 |
| 状态 | DONE |
| 完成时间 | 2026-11-09 13:07:35 |

## 二、总体评估

| 指标 | 值 |
|---|---|
| 违规总数 | 1 |
| 命中规则数 | 1 / 196 |
| **合规率** | **99.5%** |
| HIGH | 1 |

## 三、问题分布

| 分类 | 条数 |
|---|---|
| injection | 1 |

## 四、违规明细

### 1. 第 12 行 Nonconstant string passed to execute or addBatch method on an SQL statement

- **规则**：`JAVA-SB-SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE`（injection / CWE-89）
- **等级**：HIGH　**置信度**：0.95

```java
        st.execute("select * from users where name = '" + name + "'");
```

- **原因**：第 12 行调用 st.execute("select * from users where name = '" + name + "'")，SQL 字符串由方法参数 name 动态拼接而成，并非常量；该动态 SQL 又由第 11 行的 conn.createStatement() 创建出的 Statement 执行，因此构成 SQL 注入。
- **修复建议**：改用 conn.prepareStatement("select * from users where name = ?")，并通过 ps.setString(1, name) 绑定参数后调用 ps.executeQuery()。

---

*本报告由「基于大语言模型的智能代码审计系统」自动生成。判定由大模型完成，结论仅供参考，高危项建议人工复核。*
