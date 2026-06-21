# CODEBUDDY.md 本文件为 CodeBuddy 在本仓库中工作时提供指导。

## 构建 & 运行命令

### 后端（Gradle）
```bash
./gradlew bootRun                    # 启动后端，端口 8080
./gradlew build                      # 构建项目（不运行）
./gradlew build -x test              # 构建并跳过测试
./gradlew test                       # 运行全部测试（JUnit 5）
./gradlew test --tests "ClassName"   # 运行单个测试类
./gradlew test --tests "ClassName.methodName"  # 运行单个测试方法
```

### 前端（pnpm）
```bash
cd frontend
pnpm install                         # 安装依赖（要求 pnpm@10.26.2）
pnpm dev                             # 启动开发服务器，端口 5173
pnpm build                           # TypeScript 类型检查 + Vite 生产构建
```

### Docker
```bash
cp .env.example .env                # 启动前必须配置 AI_BAILIAN_API_KEY
docker-compose up -d --build         # 启动所有服务（postgres、redis、minio、app、frontend）
docker-compose logs -f app           # 查看后端日志
docker-compose down                  # 停止所有服务
```

## 架构总览

### 技术栈
- **后端**：Java 21 + Spring Boot 4.0 + Spring AI 2.0（里程碑版）+ Gradle 8.14
- **前端**：React 18 + TypeScript + Vite + TailwindCSS 4 + pnpm
- **数据**：PostgreSQL 14+ 配合 pgvector 扩展 | Redis 6+（Redisson 4.0）
- **存储**：S3 兼容对象存储（MinIO / RustFS）
- **AI**：阿里云 DashScope，通过 Spring AI 的 OpenAI 兼容模式接入（`spring.ai.openai.base-url` → `dashscope.aliyuncs.com/compatible-mode`）

### 项目结构
Gradle 单模块（`app/`）。`frontend/` 是独立的 Vite 项目。Java 包根路径为 `interview.guide`。

### 模块组织（`interview.guide` 下）

**业务模块**（`modules/`）：每个模块是自包含的领域，拥有独立的 Controller、Service、Listener、Model、Repository：
- **resume** — 简历上传、解析（Apache Tika）、通过 Redis Stream 异步分析、PDF 报告导出
- **interview** — 模拟面试会话：问题生成、答案提交、异步分批评估、汇总报告
- **knowledgebase** — 文档上传/分块、异步向量化、基于 RAG 的问答（SSE 流式响应）

**跨模块通用**（`common/`）：
- `annotation/` — `@RateLimit`（多维度限流注解）
- `aspect/` — `RateLimitAspect`（AOP + Lua 脚本实现原子滑动窗口限流）
- `ai/` — `StructuredOutputInvoker`（结构化 AI 输出，支持重试并将错误信息回注提示词）
- `async/` — `AbstractStreamConsumer`（Redis Stream 消费者模板）、Producer 辅助类
- `constant/` — `AsyncTaskStreamConstants`（Stream Key、批量大小、重试上限）
- `exception/` — `BusinessException`、`ErrorCode` 枚举、`GlobalExceptionHandler`
- `result/` — `Result<T>` 统一 API 响应包装

**基础设施**（`infrastructure/`）：
- `redis/` — `RedisService`（Stream 操作、分布式锁封装）、`InterviewSessionCache`（Redis Hash + TTL 存储活跃会话状态）
- `file/` — `FileParserService`（基于 Tika 的文档提取）
- `export/` — 使用 iText 8 + 内嵌中文字体生成 PDF
- `storage/` — S3 兼容文件上传/下载
- `mapper/` — MapStruct 映射器

### 核心架构模式

**1. Redis Stream 异步处理（生产者-消费者模式）**

所有 AI 密集型任务均通过 Redis Stream 异步执行，避免阻塞 HTTP 线程：

```
HTTP 请求 → Service 向 Stream 发布消息 → 立即返回响应给客户端
                        ↓
        @PostConstruct 启动的消费者线程轮询 Stream
                        ↓
        processMessage() → processBusiness() → AI 调用 → 更新数据库 → ACK
```

三个 Stream 消费者组，各使用单线程 `newSingleThreadExecutor`：
- `AnalyzeStreamConsumer` — `stream:resume:analyze` → 通过 AI 进行简历评分
- `VectorizeStreamConsumer` — `stream:knowledge:vectorize` → 文档向量化（pgvector）
- `EvaluateStreamConsumer` — `stream:interview:evaluate` → 分批答案评估

所有消费者继承 `AbstractStreamConsumer<T>`，基类处理：消费循环、解析、ACK、重试（最多 3 次）、状态流转（PENDING→PROCESSING→COMPLETED/FAILED）和异常处理。子类只需实现 `processBusiness()`。

Stream 常量：`BATCH_SIZE=10`、`POLL_INTERVAL_MS=1000`、`STREAM_MAX_LEN=1000`、`MAX_RETRY_COUNT=3`。

**2. 限流机制（Redis + Lua 滑动窗口）**

`@RateLimit` 注解 → `RateLimitAspect` 切面 → `rate_limit.lua` 在 Redis 中原子执行。

支持三个维度以 AND 逻辑组合（所有维度都必须通过才放行）：
- **GLOBAL** — 全局总请求数限制
- **IP** — 单个客户端 IP 限制
- **USER** — 单用户限制（当前因未实现认证，固定返回 "anonymous"）

每个维度使用两个 Redis Key：`:value`（String 类型，剩余令牌数）+ `:permits`（Sorted Set，以时间戳为 score 的分配记录）。Hash Tag `{Class:Method}` 确保同一方法的所有 Key 落在同一个 Redis Cluster Slot，以支持 Lua 脚本原子操作。

**3. AI 集成（Spring AI）**

所有 AI 调用通过 Spring AI 的 `ChatClient` → HTTP 请求到 DashScope API（OpenAI 兼容端点）。两种调用模式：
- **同步结构化输出**：`StructuredOutputInvoker.invoke()` — 调用 `chatClient.prompt().call().entity()`，将 JSON 解析为 DTO，支持可配置的重试，重试时将上次错误信息注入提示词辅助模型修正输出
- **流式（SSE）**：`chatClient.prompt().stream().content()` — 仅用于 RAG 聊天响应

提示词模板为 `resources/prompts/` 下的 `.st` 文件（10 个模板，覆盖简历分析、问题生成、评估、RAG 查询等场景）。

**4. 面试会话状态管理**

活跃会话存储在 Redis（`InterviewSessionCache` — Hash + TTL），而非数据库。缓存内容：问题 JSON、当前题目索引、状态、简历文本。缓存未命中时从 PostgreSQL 恢复并重新缓存。这避免了实时问答过程中的数据库往返，但也意味着会话状态具有易失性（Redis 重启后丢失）。

**5. RAG 管道（仅知识库模块使用）**

知识库问答使用完整 RAG 流程：用户提问 → 可选的 AI 查询改写 → Embedding API → `vectorStore.similaritySearch()`（pgvector，HNSW 索引，COSINE_DISTANCE，1024 维）→ 检索到的文档块拼接到提示词 → AI 流式响应。简历分析和面试问题生成**不使用 RAG**，它们将所有上下文直接写入提示词。

### 关键配置

环境变量（Docker 通过 `.env` 设置，本地通过 shell 设置）：
- `AI_BAILIAN_API_KEY` — **必填**。阿里云 DashScope API 密钥。
- `AI_MODEL` — 默认 `qwen-plus`，可改为 `qwen-max`、`qwen-long` 等。
- `POSTGRES_HOST/PORT/DB/USER/PASSWORD` — PostgreSQL 连接
- `REDIS_HOST/PORT` — Redis 连接
- `APP_STORAGE_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET` — S3 存储

应用配置：`app/src/main/resources/application.yml`。注意：`spring.ai.retry.max-attempts=1`（框架层不自动重试；重试逻辑在业务层的 `StructuredOutputInvoker` 中实现）。

JPA `ddl-auto`：首次启动使用 `create`，之后切换为 `update`，否则每次重启会删除所有数据。
