# 知识库与 RAG 向量检索模块说明文档

---

## 一、模块总览

知识库模块是项目中唯一使用 RAG（Retrieval-Augmented Generation）的模块，核心职责是将用户上传的文档进行切分、向量化、存储，并在用户提问时通过向量相似度检索相关片段，拼接到 AI 提示词中生成回答。

该模块涉及两个 Controller：

| Controller | 职责 | 核心流程 |
|---|---|---|
| `KnowledgeBaseController` | 知识库的增删查改、上传下载、向量化管理 | 上传 → 解析 → 异步向量化；查询 → RAG 检索 → AI 生成 |
| `RagChatController` | 基于知识库的聊天会话管理 | 创建会话 → 关联知识库 → 流式问答 |

---

## 二、完整调用链路

### 2.1 知识库上传链路

```
POST /api/knowledgebase/upload  (@RateLimit GLOBAL+IP count=3)
    │
    ▼
KnowledgeBaseController.uploadKnowledgeBase(file, name, category)
    │
    ▼
KnowledgeBaseUploadService.uploadKnowledgeBase()
    │
    ├── ① 文件校验
    │   └── FileValidationService.validateFile(file, 50MB, "知识库")
    │       └── 校验：文件非空、大小 ≤ 50MB、类型合法（PDF/DOCX/DOC/TXT/MD）
    │
    ├── ② 类型检测
    │   └── KnowledgeBaseParseService.detectContentType(file)
    │       └── ContentTypeDetectionService.detectContentType() — Tika 检测 + 扩展名兜底
    │       └── validateContentType() — 验证 MIME 是否为知识库允许类型
    │
    ├── ③ 重复检测（核心前置校验）
    │   └── FileHashService.calculateHash(file) → SHA-256 哈希
    │   └── KnowledgeBaseRepository.findByFileHash(fileHash)
    │       ├── 存在 → KnowledgeBasePersistenceService.handleDuplicateKnowledgeBase()
    │       │         → 更新访问计数 + 返回 duplicate=true
    │       └── 不存在 → 继续后续流程
    │
    ├── ④ 文档解析（提取纯文本）
    │   └── KnowledgeBaseParseService.parseContent(file)
    │       └── DocumentParseService.parseContent() — Apache Tika 解析
    │       └── 支持：PDF、DOCX、DOC、TXT、MD
    │
    ├── ⑤ 文件存储
    │   └── FileStorageService.uploadKnowledgeBase(file)
    │       └── 存储到 S3 兼容对象存储（MinIO / RustFS）
    │
    ├── ⑥ 数据库持久化
    │   └── KnowledgeBasePersistenceService.saveKnowledgeBase()
    │       └── 创建 KnowledgeBaseEntity，状态为 PENDING
    │       └── 保存到 PostgreSQL 的 knowledge_bases 表
    │
    ├── ⑦ 异步向量化（发送到 Redis Stream）
    │   └── VectorizeStreamProducer.sendVectorizeTask(kbId, content)
    │       └── 消息格式：{ kbId, content, retryCount=0 }
    │       └── 写入 Stream: knowledgebase:vectorize:stream
    │
    └── ⑧ 返回结果
        └── { knowledgeBase: {id, name, category, fileSize, contentLength, vectorStatus=PENDING},
               storage: {fileKey, fileUrl},
               duplicate: false }
```

### 2.2 异步向量化消费链路

```
应用启动 → @PostConstruct → VectorizeStreamConsumer.init()
    │
    ▼  单线程轮询 Stream: knowledgebase:vectorize:stream
    │
AbstractStreamConsumer.consumeLoop() → processMessage()
    │
    ├── parsePayload() — 解析消息 → VectorizePayload(kbId, content)
    ├── markProcessing() — 更新数据库状态为 PROCESSING
    ├── processBusiness() — 核心向量化逻辑 ↓
    │   │
    │   ▼
    │   KnowledgeBaseVectorService.vectorizeAndStore(kbId, content)
    │       │
    │       ├── ① 删除该知识库的旧向量数据
    │       │   └── VectorRepository.deleteByKnowledgeBaseId(kbId)
    │       │       └── SQL: DELETE FROM vector_store WHERE metadata->>'kb_id' = ?
    │       │
    │       ├── ② 文档切分（RAG 核心步骤）
    │       │   └── TokenTextSplitter.apply(List.of(new Document(content)))
    │       │       └── 默认参数：每个 chunk 约 500 tokens，重叠 50 tokens
    │       │
    │       ├── ③ 为每个 chunk 添加 metadata
    │       │   └── chunk.getMetadata().put("kb_id", knowledgeBaseId.toString())
    │       │       └── 统一使用 String 类型存储，确保查询一致性
    │       │
    │       └── ④ 分批向量化并存储
    │           └── 每 10 个 chunk 一批（DashScope Embedding API 限制）
    │           └── vectorStore.add(batch)
    │               └── Spring AI PgVectorStore → 调用 DashScope Embedding API
    │               └── 生成 1024 维向量 → 写入 PostgreSQL vector_store 表
    │
    ├── markCompleted() — 更新数据库状态为 COMPLETED
    ├── ackMessage() — 确认消息
    │
    └── 异常处理：
        ├── 重试次数 < 3 → retryMessage() 重新入队
        └── 重试次数 ≥ 3 → markFailed() 状态更新为 FAILED
```

### 2.3 知识库查询链路（RAG 同步）

```
POST /api/knowledgebase/query  (@RateLimit GLOBAL+IP count=10)
    │
    ▼
KnowledgeBaseController.queryKnowledgeBase(request)
    │  QueryRequest: { knowledgeBaseIds: List<Long>, question: String }
    │
    ▼
KnowledgeBaseQueryService.queryKnowledgeBase(request)
    │
    ▼
KnowledgeBaseQueryService.answerQuestion(knowledgeBaseIds, question)
    │
    ├── ① 验证 & 更新问题计数
    │   └── KnowledgeBaseCountService.updateQuestionCounts(kbIds)
    │
    ├── ② 构建查询上下文（Query Rewrite + 动态参数）
    │   └── buildQueryContext(question) → QueryContext
    │       ├── normalizeQuestion() — 去除首尾空格
    │       ├── rewriteQuestion() — AI 改写查询（可配置开关）
    │       │   └── chatClient.prompt().user(rewritePrompt).call().content()
    │       │   └── 提示词模板：knowledgebase-query-rewrite.st
    │       ├── 候选查询列表：[改写后问题, 原问题]（按序尝试）
    │       └── resolveSearchParams() — 根据问题长度动态调整检索参数
    │           ├── 长度 ≤ 4（短查询）→ topK=20, minScore=0.18
    │           ├── 长度 ≤ 12（中查询）→ topK=12, minScore=0.28
    │           └── 长度 > 12（长查询）→ topK=8,  minScore=0.28
    │
    ├── ③ 向量检索
    │   └── retrieveRelevantDocs(queryContext, knowledgeBaseIds)
    │       └── 依次用候选查询调用 vectorService.similaritySearch()
    │           ├── 有有效命中 → 返回文档列表
    │           └── 无有效命中 → 尝试下一个候选查询
    │
    ├── ④ 有效命中判断
    │   └── hasEffectiveHit(question, docs)
    │       ├── 非短 token 查询 → 直接返回 true
    │       └── 短 token 查询 → 提取核心词在文档中字面匹配确认
    │           └── 中文问句提取：正则匹配"什么是X"→提取X、"X是什么"→提取X
    │
    ├── ⑤ 构建上下文
    │   └── docs.stream().map(Document::getText).joining("\n\n---\n\n")
    │
    ├── ⑥ 构建 AI 提示词
    │   ├── systemPrompt = knowledgebase-query-system.st 渲染
    │   └── userPrompt = knowledgebase-query-user.st 渲染
    │       └── 变量：{context: 检索到的文档片段, question: 用户问题}
    │
    ├── ⑦ 调用 AI 生成回答
    │   └── chatClient.prompt().system(systemPrompt).user(userPrompt).call().content()
    │
    └── ⑧ 返回结果
        └── QueryResponse: { answer: String, knowledgeBaseId: Long, knowledgeBaseName: String }
```

### 2.4 知识库流式查询链路（RAG SSE）

```
POST /api/knowledgebase/query/stream  (@RateLimit GLOBAL+IP count=5)
    │
    ▼
KnowledgeBaseController.queryKnowledgeBaseStream(request)
    │
    ▼
KnowledgeBaseQueryService.answerQuestionStream(kbIds, question)
    │
    ├── 步骤 ①~⑥ 与同步查询相同
    │
    ├── ⑦ 流式调用 AI
    │   └── chatClient.prompt().system().user().stream().content()
    │       └── 返回 Flux<String>
    │
    └── ⑧ 流式输出归一化（探测窗口机制）
        └── normalizeStreamOutput(rawFlux)
            ├── 前 120 字符作为探测窗口，缓冲观察
            ├── 如果探测窗口中出现"未检索到相关信息"等关键词
            │   → 立即输出固定模板 + 结束流（防止长篇拒答浪费 token）
            └── 探测窗口通过 → 切换为透传模式，实时输出后续内容
```

### 2.5 RAG 聊天会话链路

```
POST /api/rag-chat/sessions/{sessionId}/messages/stream
    │
    ▼
RagChatController.sendMessageStream(sessionId, request)
    │
    ├── ① 准备消息（同步操作）
    │   └── RagChatSessionService.prepareStreamMessage(sessionId, question)
    │       ├── 加载会话 + 关联知识库
    │       ├── 保存用户消息（type=USER, completed=true）
    │       ├── 创建 AI 消息占位（type=ASSISTANT, content="", completed=false）
    │       └── 返回 AI 消息 ID
    │
    ├── ② 获取流式回答
    │   └── RagChatSessionService.getStreamAnswer(sessionId, question)
    │       └── 从会话获取关联知识库 ID 列表
    │       └── 委托 KnowledgeBaseQueryService.answerQuestionStream(kbIds, question)
    │           └── 复用完整的 RAG 流程（检索 + 流式 AI）
    │
    ├── ③ SSE 格式包装
    │   └── Flux<ServerSentEvent<String>> — 换行符转义避免破坏 SSE 格式
    │
    ├── ④ 流式完成后回调
    │   └── doOnComplete → RagChatSessionService.completeStreamMessage(messageId, fullContent)
    │       └── 更新 AI 消息内容 + 标记 completed=true
    │
    └── ⑤ 错误处理
        └── doOnError → 保存已接收内容 或 写入错误提示
```

---

## 三、实体类与数据模型

### 3.1 KnowledgeBaseEntity（PostgreSQL 表 `knowledge_bases`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 主键，自增 |
| `fileHash` | String(64) | **SHA-256 哈希**，唯一索引，用于去重 |
| `name` | String | 知识库名称（用户自定义或从文件名提取） |
| `category` | String(100) | 分类（如"Java面试"、"项目文档"） |
| `originalFilename` | String | 原始文件名 |
| `fileSize` | Long | 文件大小（字节） |
| `contentType` | String | MIME 类型 |
| `storageKey` | String(500) | S3 存储键 |
| `storageUrl` | String(1000) | S3 访问 URL |
| `uploadedAt` | LocalDateTime | 上传时间 |
| `lastAccessedAt` | LocalDateTime | 最后访问时间 |
| `accessCount` | Integer | 访问次数 |
| `questionCount` | Integer | 被提问次数 |
| `vectorStatus` | VectorStatus | 向量化状态（PENDING/PROCESSING/COMPLETED/FAILED） |
| `vectorError` | String(500) | 向量化失败时的错误信息 |
| `chunkCount` | Integer | 切分后的向量分块数量 |

唯一索引：`idx_kb_hash(fileHash)` — 保证同一文件不会重复入库。

### 3.2 VectorStatus 枚举

```
PENDING → PROCESSING → COMPLETED
                      → FAILED（重试 ≤ 3 次后仍失败）
```

### 3.3 向量存储表（PostgreSQL 表 `vector_store`，Spring AI 自动管理）

由 Spring AI PgVectorStore 自动创建和管理，结构：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | 主键 |
| `content` | TEXT | 原始文本内容（chunk） |
| `metadata` | JSON | 元数据，含 `kb_id`（知识库 ID，String 类型） |
| `embedding` | VECTOR(1024) | 1024 维向量 |

### 3.4 请求/响应 DTO

**QueryRequest**（查询请求）:
```java
record QueryRequest(
    @NotEmpty List<Long> knowledgeBaseIds,  // 支持多知识库
    @NotBlank String question
)
```

**QueryResponse**（查询响应）:
```java
record QueryResponse(
    String answer,
    Long knowledgeBaseId,        // 主知识库 ID（兼容前端）
    String knowledgeBaseName     // 知识库名称（多知识库用顿号分隔）
)
```

---

## 四、文档前置校验与重复检测

### 4.1 文件校验流程

```
文件上传 → FileValidationService.validateFile()
    ├── 文件非空检查
    ├── 文件大小 ≤ 50MB
    ├── MIME 类型检测（Tika 检测优先，扩展名兜底）
    │   └── 允许类型：application/pdf, application/msword,
    │       application/vnd.openxmlformats-officedocument.wordprocessingml.document,
    │       text/plain, text/markdown
    └── 扩展名检查（针对 MD 文件：.md 扩展名检测）
```

### 4.2 重复检测机制

```
上传文件 → FileHashService.calculateHash(file)
    │
    └── SHA-256 哈希（对文件字节流计算）
        │
        ▼
    KnowledgeBaseRepository.findByFileHash(fileHash)
        ├── 已存在 → handleDuplicateKnowledgeBase()
        │   ├── 递增访问计数（accessCount++）
        │   ├── 更新最后访问时间
        │   ├── 保存到数据库
        │   └── 返回 { duplicate: true, knowledgeBase: 已有记录 }
        │
        └── 不存在 → 继续正常上传流程
```

关键点：`fileHash` 字段有唯一索引（`idx_kb_hash`），数据库层也保证不会出现重复记录。

---

## 五、RAG 文档切分思路

### 5.1 切分工具

使用 Spring AI 内置的 `TokenTextSplitter`（基于 tiktoken 算法的 Token 计数切分）。

```java
this.textSplitter = new TokenTextSplitter();  // 默认参数
```

### 5.2 默认切分参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `defaultChunkSize` | 500 | 每个 chunk 的目标 Token 数 |
| `minChunkSizeChars` | 350 | chunk 最小字符数 |
| `minChunkLengthToEmbed` | 5 | 过短的 chunk 不做向量化 |
| `maxNumChunks` | 10000 | 单文档最大 chunk 数 |
| `keepSeparator` | true | 保留分隔符 |
| `overlapSize` | 50 | 相邻 chunk 之间的重叠 Token 数 |

### 5.3 切分流程

```
原始文档内容（纯文本）
    │
    ▼  TokenTextSplitter.apply(List.of(new Document(content)))
    │
    ├── 1. 按 Token 数切分（每个 chunk ≈ 500 tokens）
    ├── 2. 相邻 chunk 有 50 tokens 重叠（保证语义连续性）
    ├── 3. 过滤掉太短的 chunk（< 5 字符）
    │
    ▼
List<Document> chunks  ← 每个chunk是一个Spring AI Document对象
    │
    ├── 为每个 chunk 添加 metadata
    │   └── chunk.getMetadata().put("kb_id", knowledgeBaseId.toString())
    │       └── 统一为 String 类型，确保查询时类型一致性
    │
    └── 分批向量化（每批 ≤ 10 个 chunk）
        └── DashScope Embedding API 批量大小限制
```

### 5.4 切分设计要点

- **Token 级切分**而非字符级：Token 是 LLM 的基本单位，Token 切分更精准
- **重叠设计**：相邻 chunk 重叠 50 tokens，避免关键信息恰好在切割边界被截断
- **Metadata 绑定**：每个 chunk 携带 `kb_id`，查询时可按知识库过滤



固定大小拆分能够保证chunk的数量是完整的，但是无法保证语义的连贯性，**适用于无格式的纯文本文件（PDF解析之后的结果）**

对于后续升级的话，或者一般采用的做法都是：

- **递归字符拆分**（段落---》句子----》短句 /n 或者是空格拆分） 能够保证一句话被完整的保留下来，但是语句会有长短，**对于较长的语句使用固定大小拆分**
  - 适用于大多数的场景，通用向

- **语义拆分**：每句话都做embedding向量化，在比较相似度，相似度比较高的不拆分，直到遇到比较低的分界点





---

## 六、向量存储机制

### 6.1 存储架构

```
Spring AI VectorStore 接口
    │
    ▼  实现类
PgVectorStore（Spring AI 自动配置）
    │
    ├── 写入：vectorStore.add(List<Document>)
    │   ├── Document → 调用 DashScope Embedding API → 生成 1024 维向量
    │   └── 写入 PostgreSQL 的 vector_store 表
    │
    └── 查询：vectorStore.similaritySearch(SearchRequest)
        └── 查询文本 → Embedding → 在 pgvector 中做相似度计算 → 返回 TopK 结果
```

### 6.2 Embedding 模型

| 配置项 | 值 |
|---|---|
| 模型 | `text-embedding-v3`（阿里云 DashScope） |
| 向量维度 | 1024 |
| 配置方式 | `spring.ai.openai.embedding.options.model: text-embedding-v3` |

底层通过 Spring AI 的 OpenAI 兼容模式调用：HTTP POST → `dashscope.aliyuncs.com/compatible-mode/v1/embeddings`。

### 6.3 pgvector 配置

| 配置项 | 值 | 说明 |
|---|---|---|
| `index-type` | HNSW | 近似最近邻索引，查询速度快 |
| `distance-type` | COSINE_DISTANCE | 余弦距离 |
| `dimensions` | 1024 | 与 Embedding 模型输出维度一致 |
| `initialize-schema` | true（开发）/ false（生产） | 是否自动建表 |

### 6.4 向量数据的删除与重建

重新向量化时（`revectorize`），先删旧向量再重新切分存储：

```java
// VectorRepository — 直接 SQL 删除
DELETE FROM vector_store WHERE metadata->>'kb_id' = ?
   OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
```

兼容 `kb_id` 的 String 和 Long 两种存储格式。

---

## 七、相似度检索方法

### 7.1 检索核心方法

```java
KnowledgeBaseVectorService.similaritySearch(query, knowledgeBaseIds, topK, minScore)
```

### 7.2 检索流程

```
查询文本 → Spring AI SearchRequest
    │
    ├── 构建 SearchRequest
    │   ├── query: 查询文本
    │   ├── topK: 返回的最大结果数
    │   ├── similarityThreshold: 最低相似度阈值
    │   └── filterExpression: "kb_id in ['1', '2']" （知识库过滤）
    │
    ├── 主路径：vectorStore.similaritySearch(request)
    │   └── 查询文本 → DashScope Embedding API → 1024 维向量
    │   └── pgvector HNSW 索引 + COSINE_DISTANCE 计算 → 返回 TopK 结果
    │
    └── 回退路径（filterExpression 执行失败时）
        └── similaritySearchFallback()
            ├── 不带知识库过滤，扩大 topK 到 3 倍
            ├── 拉取结果后本地过滤（按 metadata.kb_id 匹配）
            └── 截取 topK 条返回
```

### 7.3 相似度算法

- **算法**：余弦相似度（COSINE_DISTANCE）
- **pgvector 实现**：`<=>` 操作符（余弦距离），1 - 余弦相似度
- **HNSW 索引**：近似最近邻搜索，时间复杂度 O(log n)，牺牲微小精度换取数量级速度提升

### 7.4 动态检索参数

`KnowledgeBaseQueryService` 根据问题长度动态调整 topK 和 minScore：

| 问题长度（去空格后） | topK | minScore | 场景 |
|---|---|---|---|
| ≤ 4 字符 | 20 | 0.18 | 短关键词，降低阈值多召回 |
| ≤ 12 字符 | 12 | 0.28 | 中等长度 |
| > 12 字符 | 8 | 0.28 | 长描述，提高阈值精准匹配 |

设计思路：短查询信息量少，降低阈值多召回文档；长查询信息量充足，提高阈值追求精准。

### 7.5 知识库过滤表达式

```java
// 构建过滤表达式
"kb_id in ['1', '2', '3']"

// Spring AI 会将其翻译为 pgvector 的 SQL WHERE 条件
```

注意：`kb_id` 在 metadata 中统一存储为 String 类型，过滤表达式中值也用引号包裹。

### 7.6 短查询命中确认

对短 token 查询（如"进程"、"Redis"），向量检索可能返回弱相关结果。增加字面匹配确认：

```
短 token 查询（≤6 个中文字符 或 2~20 字母数字）
    │
    ├── 提取核心词
    │   ├── "什么是进程" → 正则提取 → "进程"
    │   ├── "进程是什么" → 正则提取 → "进程"
    │   └── 无法识别 → 原样返回
    │
    └── 在检索到的文档中字面匹配核心词
        ├── 找到 → hasEffectiveHit = true → 继续生成回答
        └── 找不到 → hasEffectiveHit = false → 返回"未检索到相关信息"
```

---

## 八、返回值说明

### 8.1 上传接口返回

```json
{
  "knowledgeBase": {
    "id": 1,
    "name": "Java面试指南",
    "category": "Java面试",
    "fileSize": 1024000,
    "contentLength": 50000,
    "vectorStatus": "PENDING"
  },
  "storage": {
    "fileKey": "knowledgebase/uuid.pdf",
    "fileUrl": "http://localhost:9000/interview-guide/knowledgebase/uuid.pdf"
  },
  "duplicate": false
}
```

### 8.2 同步查询返回

```json
{
  "answer": "根据知识库内容，进程是操作系统资源分配的基本单位...",
  "knowledgeBaseId": 1,
  "knowledgeBaseName": "Java面试指南、操作系统笔记"
}
```

### 8.3 流式查询返回

SSE 事件流，每个事件的 data 字段是一个文本片段：

```
data:根据知识库内容，
data:进程是操作系统
data:资源分配的基本单位...
```

### 8.4 无结果时的统一返回

所有查询模式（同步/流式）在未检索到相关信息时统一返回：

```
"抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。"
```

流式模式中，通过 120 字符探测窗口机制，一旦检测到 AI 生成的内容包含"未检索到相关信息"等关键词，立即替换为固定模板并结束流，避免生成大段无意义的拒答文本。