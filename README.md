# graduation-audit · 基于大语言模型的智能代码审计系统

> 前端仓库：[audit-web](https://github.com/ZN0926/audit-web)（Vue 3 + Vite，端口 5173）

本科毕业设计项目。输入一段 Java 或 Python 源码，系统用"从示例中蒸馏出来的规则库"逐块交给大模型判定，
输出一份带行号定位、违规解释和修复建议的审计报告。

> 当前状态：**后端已完成**（M1–M7：切片 → 规则库 → 审计编排 → 报告 → 10 个接口 + CORS + 全局异常）。
> 前端是独立仓库 `F:\毕业设计\audit-web`（Vue3），构建后 `dist` 拷进 `src/main/resources/static` 即为单 jar。
> 施工与验收记录见 `docs/实现分块/`。

---

## 1. 这个系统解决什么问题

传统规则扫描器（SonarQube/Checkmarx）规则靠人工写，一条一条写、一条一条维护，成本高；
纯大模型扫描又会"看见什么都能说成漏洞"，误报高、无法定位。

本系统的做法是把两者接起来：

1. **规则从规范里来**：把编码规范/手册（阿里 P3C）或现成规则库（SpotBugs / pylint / bandit）抽成结构化规则（rule），存库版本化；A 通道（LLM 抽取 + 证据回查）、B 通道（结构化直转）两条路；
2. **审计按规则判**：把代码切片，连同"相关规则"一起交给模型，让它只回答"这条规则违没违反、在第几行"；
3. **结果可校验**：行号必须在切片范围内、规则 id 必须在规则库里、JSON 必须能解析，否则丢弃重试。

这样模型的自由度被限制在"按给定规则判定"上，幻觉空间被压到最小。

---

## 2. 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 4.1.2 / Java 21 / Maven 3.9.4（注意：Boot 4 的 Web 依赖叫 `spring-boot-starter-webmvc`，不叫 `-web`） |
| 持久化 | MySQL 8 + Spring Data JPA（`ddl-auto: update` 自动建表） |
| 大模型 | OpenAI 兼容接口（默认 DeepSeek `deepseek-chat`），`RestClient` 直连，`temperature=0` |
| 代码解析 | JavaParser（Java）、Python `ast` 子进程（Python） |
| 前端 | Vue3 + Vite + Element Plus + ECharts（M8 阶段） |

---

## 3. 怎么跑起来（别人拿到这份代码的完整步骤）

### 3.0 需要先准备

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | 21 | 编译运行 |
| Maven | 3.9+ | 构建 |
| MySQL | 8.0 | 存规则、任务、结果 |
| 大模型 key | 任一 OpenAI 兼容服务 | 规则抽取 + 代码判定（可以用 DeepSeek、千问 MaaS、OpenAI 等） |

### 3.1 建库建账号

```sql
CREATE DATABASE IF NOT EXISTS audit_db
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'audit'@'localhost' IDENTIFIED BY 'Audit@2026';
GRANT ALL PRIVILEGES ON audit_db.* TO 'audit'@'localhost';
FLUSH PRIVILEGES;
```

> 想用自己的库名/账号：设环境变量 `DB_URL` / `DB_USER` / `DB_PASSWORD` 覆盖即可，**不用改 yaml**。

### 3.2 配 key（换成你自己的）

```powershell
# Windows 永久生效（新开终端生效）
setx LLM_API_KEY "sk-你自己的key"
```

```bash
# macOS / Linux
export LLM_API_KEY="sk-你自己的key"
```

如果你用的不是默认网关/模型，再设两个（可选）：

```powershell
setx LLM_BASE_URL "https://api.deepseek.com"   # 只写到 /v1 之前的路径
setx LLM_MODEL "deepseek-chat"
```

> 代码里没有任何明文 key，`application.yaml` 里只有 `${LLM_API_KEY:}` 占位符，**换 key 不用改代码**。

### 3.3 启动

```powershell
cd graduation-audit
mvn spring-boot:run
```

### 3.4 灌规则（关键一步，不做的话审出来永远是 0 条）

规则库不随仓库分发（`docs-source/` 里的 pylint/SpotBugs/阿里手册语料有各自许可证）。
仓库里只带了一份**自己写的 8 条基线规则**，先导入它让系统能出结果：

```powershell
curl -X POST "http://localhost:8080/api/rules/import?path=docs/seed/manual-rules.json"
```

返回 `{"total":8,"imported":8,...}` 即成功。确认：

```powershell
curl "http://localhost:8080/api/rules/stats"
# {"total":8,"byLanguage":{"java":8}, ...}
```

想扩充到几百条，两条路（详见 `docs/实现分块/块4-规则蒸馏.md`）：

- **B 通道**：从 pylint / SpotBugs / bandit 仓库解析成 JSON，再 `/api/rules/import`；
- **A 通道**：把阿里手册之类的规范文本放进 `docs-source/`，调 `POST /api/rules/distill?docPath=...&language=java`，由模型按 Schema 抽取，`evidence_quote` 必须能在原文逐字搜到否则丢弃。

### 3.5 验证

浏览器打开 <http://localhost:8080/api/ping>，应返回：

```json
{"status":"ok","db":"connected","time":"..."}
```

- `db: connected` → 数据库通了；
- 若 `failed` → 检查 MySQL 是否启动、`audit_db` 是否存在、账号密码是否对；
- 启动日志里会打印 `上传目录：<绝对路径>（已实测可写）`，写不进会自动退回系统临时目录。

### 3.6 用起来

打开 <http://localhost:8080> 上传 `.java` / `.py` 文件，或者直接调接口：

```powershell
curl -X POST -F "file=@demo/BadDemo.java" http://localhost:8080/api/audit
# 返回 {"taskId":1,...}，然后看 /api/audit/tasks/1 与 /api/report/1
```

接口全集见 `docs/接口说明.md`。


---

## 4. 目录结构与每个文件的职责

```
graduation-audit/
├── pom.xml                     依赖清单
├── README.md                   本文件
└── src/main/
    ├── java/com/audit/
    │   ├── GraduationAuditApplication.java   启动类
    │   ├── config/                          配置类
    │   │   ├── LlmProperties.java           llm.* 配置绑定（baseUrl/apiKey/model/timeout）
    │   │   ├── LlmConfig.java               构造带超时的 RestClient
    │   │   ├── WebConfig.java               跨域（前端 5173 → 后端 8080）
    │   │   └── GlobalExceptionHandler.java  统一异常 → JSON
    │   ├── controller/                      接口层（只做参数校验与转发）
    │   │   ├── PingController.java          健康检查：Web + 数据源
    │   │   ├── UploadController.java        文件上传、建任务
    │   │   ├── AuditController.java         任务列表 / 进度 / findings
    │   │   ├── RuleController.java          规则蒸馏、查询、删除
    │   │   └── ReportController.java        报告查询与 Markdown 导出
    │   ├── service/                         业务层（系统的真正逻辑）
    │   │   ├── LlmService.java              唯一的大模型出口，重试与 JSON 清洗
    │   │   ├── RuleDistillService.java      ★规则蒸馏：示例 → 规则
    │   │   ├── CodeParseService.java        代码切片，行号校正
    │   │   ├── AuditService.java            审计编排：切片→判定→落库
    │   │   └── ReportService.java           合规率统计、报告生成
    │   ├── model/                           数据模型
    │   │   ├── Rule.java                    规则（表 rule）
    │   │   ├── AuditTask.java               任务（表 audit_task）
    │   │   ├── Finding.java                 判定结果（表 finding）
    │   │   ├── Report.java                  报告（表 report）
    │   │   └── CodeChunk.java               代码切片（内存对象，不入库）
    │   ├── repository/                      JPA 接口，方法名即查询
    │   ├── prompt/PromptTemplates.java      三套提示词（蒸馏/审计/复核）
    │   └── util/
    │       ├── JsonUtil.java                剥 ```json 围栏、抠数组/对象
    │       └── FileUtil.java                存文件、判语言、读源码
    └── resources/
        ├── application.yaml                 端口、数据源、llm、日志
        ├── rules/                           规则库 JSON（版本化备份）
        ├── examples/java/                   蒸馏用 Java 示例（含正反例）
        ├── examples/python/                 蒸馏用 Python 示例
        └── python/parse_python.py           Python 切片脚本
```

---

## 5. 数据模型

四张表由 JPA 根据实体自动建（`ddl-auto: update`），字段设计见下表。

### rule —— 规则

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 自增 |
| rule_id | varchar(64) unique | 业务主键，如 `JAVA-SQLI-001` |
| language | varchar(16) | `java` / `python` |
| category | varchar(64) | 攻击类别，如 `SQL注入` |
| cwe | varchar(32) | `CWE-89` |
| title | varchar(200) | 一句话规则名 |
| description | text | 规则说明 |
| positive_example | text | 违规示例 |
| negative_example | text | 合规示例 |
| severity | varchar(16) | HIGH / MEDIUM / LOW |
| detection_hint | text | 让模型"看什么"的提示 |
| evidence_quote | varchar(200) | 规则来源依据（防幻觉） |
| source_doc | varchar(64) | 来源文档：alibaba-p3c / spotbugs / pylint / bandit |
| source_ref | varchar(64) | 来源编号：条款号（1）或消息号（E0601 / SQL_NONCONSTANT_STRING_…） |
| detectability | varchar(16) | pattern / semantic / manual，manual 不进自动检测集 |
| canonical_key | varchar(64) | **跨语言归键**：`CWE-89` 或 `CAT-injection`，用来把同类漏洞在多种语言下的规则串成一组 |
| created_at | datetime | |

### audit_task —— 审计任务

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| file_name | varchar(255) | 原始文件名 |
| language | varchar(16) | |
| status | varchar(16) | PENDING / RUNNING / DONE / FAILED |
| total_chunks / done_chunks | int | 进度 |
| error_msg | varchar(500) | 失败原因 |
| created_at / finished_at | datetime | |

### finding —— 判定结果

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| task_id | bigint | 所属任务 |
| rule_id | varchar(64) | 命中的规则 |
| file_path | varchar(512) | |
| start_line / end_line | int | **1-based** |
| code_snippet | text | 命中片段，报告页高亮 |
| violated | boolean | true=违规 |
| severity | varchar(16) | |
| reason | text | 为什么判违规 |
| fix_suggestion | text | 怎么改 |
| confidence | double | 0~1 |

### report —— 报告

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | |
| task_id | bigint | |
| summary_text | text | 一句话结论 |
| compliance_rate | double | 0~1 |
| markdown | mediumtext | 正文 |
| created_at | datetime | |

---

## 6. 接口清单

统一前缀 `/api`，全部返回 JSON。状态列的 M1–M8 指实现里程碑。

| # | 方法 | 路径 | 说明 | 状态 |
|---|---|---|---|---|
| 1 | GET | `/api/ping` | 健康检查（DB 连通性） | 待实现 M0 |
| 2 | POST | `/api/upload` | 上传源码文件，建任务 | 待实现 M5 |
| 3 | POST | `/api/audit/tasks/{id}/run` | 触发审计 | 待实现 M5 |
| 4 | GET | `/api/audit/tasks` | 任务列表（倒序） | 待实现 M5 |
| 5 | GET | `/api/audit/tasks/{id}` | 任务详情与进度 | 待实现 M5 |
| 6 | GET | `/api/audit/tasks/{id}/findings` | 判定明细 | 待实现 M5 |
| 7 | GET | `/api/report/{taskId}` | 报告 JSON | 待实现 M6 |
| 8 | GET | `/api/report/{taskId}/markdown` | 报告 Markdown（text/plain） | 待实现 M6 |
| 9 | POST | `/api/rules/distill?language=java` | 触发规则蒸馏 | 待实现 M4 |
| 10 | GET | `/api/rules?language=java` | 规则列表（可按语言过滤） | 待实现 M4 |
| 11 | DELETE | `/api/rules/{ruleId}` | 删除一条规则 | 待实现 M4 |

---

## 7. 一次审计的完整流程

```
上传文件
  → 存到 uploads/，按扩展名判语言
  → 建 AuditTask（PENDING）
  → CodeParseService 切片（按方法/函数，>80 行再切）
  → 按语言加载规则库
  → 逐块：拼提示词（代码 + 规则 JSON）→ LlmService → 解析 violations
  → 每条结果写 Finding，更新 done_chunks
  → 全部完成 → ReportService 算合规率、生成 Markdown
```

合规率 = `1 - 违规规则数 / 参检规则数`（同一规则在多处命中只算一次）。

---

## 8. 开发顺序（每个里程碑都可独立验收）

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 | 4 实体 + 4 Repository | `SHOW TABLES` 出现 4 张表 |
| M2 | LlmService | 调一次模型能拿到中文回答 |
| M3 | 代码切片 | 切片行号与源文件完全一致 |
| M4 | 规则蒸馏 | 入库 ≥20 条规则 |
| M5 | 审计编排 | 上传含缺陷的文件，finding 有违规记录 |
| M6 | 报告 + 接口收尾 | curl 能把 11 个接口跑通 |
| M7 | 前端（Vue3） | 四个页面能用 |
| M8 | 评测 | 双语言各 50 个样本，三组对比 |

---

## 9. 几条不能破的约定

1. **行号一律 1-based**，切片边界、finding 行号、报告高亮统一，`lines.get(i - 1)` 别忘；
2. **`temperature` 固定 0**，判定要可复现，论文里要能重复实验结果；
3. **key 只走环境变量**（`${LLM_API_KEY:}`），yaml 里不出现明文；
4. **规则是判定的唯一依据**：提示词里必须出现"不得新增规则"；
5. **报告里每条结论都要能追到 `rule_id + 行号`**，说不清来源的判定宁可丢弃。
