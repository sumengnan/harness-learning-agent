# Harness 学习小助手 · Agent 核心（子项目 B）

Java 21 + Spring Boot 3.3.4 + langchain4j 0.35.0 的 6 层自主决策 Agent（L1 信息边界 / L2 工具系统 / L3 执行编排 / L4 记忆与状态 / L5 评估与观测 / L6 约束校验与恢复）。

## 命令

- 编译：`mvn -q compile`
- 全量测试：`mvn test`
- 单个测试类：`mvn -Dtest=IntegrationSurveyTest test`
- 打包：`mvn -q package`

## 测试约定

- 单元/集成测试用内存 SQLite（`jdbc:sqlite::memory:`）+ `FakeChatModel` 测试替身，**无需真实 API key** 即可全绿。
- 端到端集成测试见 `IntegrationSurveyTest`：用 `FakeChatModel` 脚本驱动 planner（检索→final）与 critic（L5 判 pass），桩一个 `local_retrieve` 工具跑通 `AgentLoop`。其中的相关性过滤走本地 ONNX embedding（`BgeSmallZhEmbeddingModel`），首次加载模型较慢属正常。
- **约定（当前无 live 测试）**：将来需要真实 LLM / 联网搜索的冒烟测试，请用 `@Tag("live")` 标注，使其默认不随 `mvn test` 运行；跑它们需设环境变量 `DEEPSEEK_API_KEY`（LLM）与 `TAVILY_API_KEY`（Web 搜索），例如 `mvn test -Dgroups=live`。截至目前项目中尚无任何以此标注的 live 测试。
- SQLite 为单写者，生产与测试均已固定 HikariCP `maximum-pool-size=1`。

## 配置

- LLM：`agent.llm.*`（DeepSeek 兼容 OpenAI 接口，`DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL`）。
- 数据库路径：`AGENT_DB`（默认 `./data/agent.db`，启动自动建 `data/` 目录）。
- 相关性阈值：`agent.filter.relevance-threshold`（默认 0.80）。
