# 项目规则（AI 与人都要遵守）

## 这是一什么项目

毕业设计：基于大语言模型的智能代码审计系统。Spring Boot 后端，审计 Java / Python 源码，
用"规则库 + 模型判定"给出行级违规定位与修复建议。

## 硬约定

1. **Java 21**，Spring Boot 4.1.2，包根 `com.audit`。不要改 pom 的 `<java.version>`。
2. **分层不许穿透**：`controller` 只调 `service`；`service` 只调 `repository` / `LlmService`；
   `model` 是纯数据类；`repository` 只写查询方法。controller 里不出现 SQL，service 里不出现 `@RequestMapping`。
3. **所有模型调用必须走 `LlmService`**。代码里不允许出现第二个 `RestClient` 或 `HttpClient`。
   `temperature` 固定 0，不要为了"更有创意"改它。
4. **密钥不进代码、不进 yaml**。`LLM_API_KEY` 从环境变量读，yaml 里写 `${LLM_API_KEY:}`。
5. **新增 Maven 依赖要先说**，说明为什么、体积多大、能不能用 JDK 自带能力替代。
6. **一次只做一个块**。施工文件在 `docs/实现分块/`，从 `块1-基础链路.md` 开始，
   做完一块、验收通过、再开下一块。不要一次实现多块，也不要在做块 3 时"顺手"改块 5 的代码。
7. **行号是真值**：切片出来的 `startLine/endLine` 是 1-based 且含端点。
   任何地方用 `List.get()` 取行，记得 `get(line - 1)`。报告里的定位错一行就是硬伤。
8. **幻觉要拦在代码里，不要指望提示词**：模型返回的 `ruleId` 必须在本次给出的规则清单里，
   行号必须落在块区间内，越界就丢弃并打 `log.warn`。新增任何"让模型自己判断"的地方，都要配对应的校验。

## 写代码的风格

- Lombok：实体用 `@Data`，service 用 `@RequiredArgsConstructor` + `final` 字段，日志用 `@Slf4j`。
- 异常：不要吞掉。要么 `log.error` 带上下文，要么往上抛给 `GlobalExceptionHandler`。
- 注释：只写"为什么这么做"，不写"这行在干什么"。中文注释可以，代码标识符用英文。
- 不要提前抽象：三个地方重复了再抽接口，一处就直接写。

## 数据库

- 库 `audit_db`，账号 `audit`，密码 `Audit@2026`，字符集 `utf8mb4`。
- 表结构由 JPA `ddl-auto: update` 生成；改动实体字段时注意已有数据的兼容（加字段给默认值，不要直接改类型）。
- MySQL 服务是手动启动的，重启电脑后先 `net start MySQL80`。

## 交付物位置

| 东西 | 位置 |
|---|---|
| 施工文件（6 块） | `docs/实现分块/` |
| 实现过程记录（做了什么/遇到什么/下一步） | `..\docs\03-实现过程记录.md`（工程外，`F:\毕业设计\docs`） |
| 项目说明 | `README.md`（工程根） |
| 演示用的坏代码 | `demo/` |
