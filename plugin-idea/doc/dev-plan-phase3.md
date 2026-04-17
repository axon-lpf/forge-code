# CodeForge Phase 3 — 对标 Claude Code / CodeMaker 全功能补齐计划

> **目标**：补齐与 Claude Code、CodeMaker 的核心功能差距，建立差异化竞争优势。
> **前置条件**：Phase 2 全部完成 ✅（P2-10 多模态输入合并至 Phase 3）
> **预计工期**：15 个工作日
> **开始日期**：2026-04-16

---

## 一、竞品对标分析（Phase 3 视角）

### 1.1 对标 CodeMaker 工具配置面板

| 功能 | CodeMaker | CodeForge 现状 | 差距 |
|------|-----------|----------------|------|
| 工具开关 UI（Auto/开启/关闭）| ✅ 可视化面板 | ❌ 无 | 🔴 P0 |
| Plan Mode | ✅ 先规划后执行 | ❌ 无 | 🔴 P0 |
| Memory 工具 | ✅ 跨会话记忆 | ❌ 无 | 🟡 P1 |
| 需求澄清工具 | ✅ AI 主动提问 | ❌ 无 | 🟡 P1 |
| 研发知识集 | ✅ 团队知识库 | ❌ 无 | 🟢 P2 |
| Skills 工具 | ✅ 可插拔技能 | ❌ 无 | 🟢 P2 |
| MCP Server | ✅ 外部工具接入 | ❌ 无 | 🟡 P1 |

### 1.2 对标 CodeMaker 对话框功能

| 功能 | CodeMaker | CodeForge 现状 | 差距 |
|------|-----------|----------------|------|
| + 号菜单（图片/文档/指令/知识）| ✅ 完整 | ❌ 无 | 🔴 P0 |
| 图片粘贴 + 点击选择 | ✅ 支持 | ❌ P2-10 未完成 | 🔴 P0 |
| 文档附加（PDF/TXT/MD）| ✅ 支持 | ❌ 无 | 🔴 P0 |
| / Slash Commands | ✅ 完整 | ❌ 无 | 🔴 P0 |
| @ 知识库引用 | ✅ 支持 | ⚠️ 只有 @文件 | 🟡 P1 |
| 附件预览区 | ✅ 顶部缩略图 | ❌ 无 | 🔴 P0 |
| 一键生成 Project Rules | ✅ AI 自动生成 | ❌ 无 | 🟡 P1 |
| Token 用量显示 | ✅ 底部实时显示 | ❌ 无 | 🟡 P1 |

### 1.3 对标 Claude Code 核心能力

| 功能 | Claude Code | CodeForge 现状 | 差距 |
|------|-------------|----------------|------|
| Inline Edit（行内编辑）| ✅ ⌘K 唤起 | ❌ 无 | 🔴 P0 |
| 错误自动修复循环 | ✅ 编译失败自动修 | ❌ 无 | 🔴 P0 |
| 消息 👍👎 反馈 + 重新生成 | ✅ 支持 | ❌ 无 | 🟡 P1 |
| 网络搜索工具 | ✅ 支持 | ❌ 无 | 🟢 P2 |

### 1.4 其他核心功能缺口

| 功能 | 说明 | 差距 |
|------|------|------|
| Tab 代码补全优化 | FIM 格式、防抖缓存、质量提升 | 🔴 P0 |
| 自定义 Prompt 模板管理 | 内置常用模板 + 用户自定义 | 🟡 P1 |
| 代码 Hover 快速解释 | 鼠标悬停 Alt 键触发 | 🟡 P1 |
| 知识库（私有文档 RAG）| 上传文档建立私有知识库 | 🟢 P2 |
| 使用量统计 Dashboard | Token/费用/次数可视化 | 🟢 P2 |
| 智能模型路由 | 按任务类型自动选模型 | 🟢 P2 |
| 测试自动生成 + 执行验证 | 一体化测试工作流 | 🟢 P2 |

---

## 二、分步开发计划

### Step A1：对话框 + 号菜单（0.5 天）🔴 P0

**目标**：在输入框左下角添加 `+` 按钮，展开包含四个选项的菜单。

```
+ 按钮展开菜单：
  🖼 图片     — 选择/粘贴图片附加
  📄 文档     — 附加 PDF/TXT/MD 文件
  / 指令      — 打开 Slash Commands 面板
  @ 知识      — 引用知识库条目（P2 阶段实现，暂时隐藏）
```

**文件清单**：
```
修改: webui/index.html — 添加 + 按钮及弹出菜单 DOM
修改: webui/css/style.css — + 菜单样式
修改: webui/js/chat.js — + 菜单交互逻辑
```

---

### Step A2：图片附加完整实现（1 天）🔴 P0

**合并 P2-10（T27/T28），并扩展为完整实现**：
- Ctrl+V 粘贴图片 → 底部缩略图预览区显示
- `+` 菜单点击图片 → 调用 Kotlin 侧文件选择对话框
- 支持多张图片（最多 5 张），可逐张删除
- 不支持 Vision 的模型激活时，图片选项置灰并提示

**附件预览区 UI**（顶部，类 CodeMaker 样式）：
```
┌─────────────────────────────────────┐
│ [🖼 screenshot.png ✕] [🖼 ui.png ✕] │  ← 附件预览行
├─────────────────────────────────────┤
│ 输入框...                            │
└─────────────────────────────────────┘
```

**文件清单**：
```
修改: webui/index.html — 附件预览区 DOM
修改: webui/css/style.css — 附件预览样式
修改: webui/js/chat.js — 图片粘贴/选择/预览/删除逻辑
修改: toolwindow/ForgeChatPanel.kt — handleImageInput + 文件选择对话框
```

---

### Step A3：文档附加（1 天）🔴 P0

**目标**：通过 `+` 菜单或拖拽，附加本地文档文件作为上下文。

**支持格式**：
- `.md` / `.txt` / `.log` — 直接读取文本内容
- `.pdf` — 提取纯文本（使用 PDFBox 或 iText）
- `.java` / `.kt` / `.py` 等代码文件 — 直接读取

**实现要点**：
- 文档内容注入消息上下文（不超过 8000 字符，超出截断并提示）
- 附件预览区显示文档文件名缩略图
- 发送消息时，文档内容以 `[附件: filename.pdf]\n内容...\n[/附件]` 格式插入

**文件清单**：
```
新增: util/DocumentExtractor.kt — 多格式文档文本提取
修改: toolwindow/ForgeChatPanel.kt — handleDocumentInput
修改: webui/js/chat.js — 文档附加 UI 交互
```

---

### Step A4：/ Slash Commands 系统（1 天）🔴 P0

**目标**：输入框输入 `/` 时弹出指令选择面板，快捷触发常用操作。

**内置指令列表**：

| 指令 | 功能 |
|------|------|
| `/clear` | 清空当前会话消息 |
| `/help` | 显示功能使用帮助 |
| `/rules` | 查看/编辑当前 .codeforge.md 规则 |
| `/checkpoint` | 查看 Checkpoint 列表，选择回滚 |
| `/export` | 将当前会话导出为 Markdown 文件 |
| `/model` | 快速切换模型 |
| `/review` | 切换到 Review Tab 并刷新文件列表 |
| `/commit` | 触发 AI 生成 Commit Message |
| `/plan` | 切换到 Plan Mode（先规划后执行） |
| `/tools` | 打开工具配置面板 |

**UI 交互**：
- 输入 `/` → 弹出指令列表（可模糊搜索）
- 方向键选择 + Enter 执行
- ESC 关闭面板

**文件清单**：
```
修改: webui/index.html — Slash 面板 DOM
修改: webui/css/style.css — Slash 面板样式
修改: webui/js/chat.js — / 触发逻辑 + 指令执行
修改: toolwindow/ForgeChatPanel.kt — 处理 slash 指令的 JS Bridge 消息
```

---

### Step A5：工具配置面板（1 天）🔴 P0

**目标**：类似 CodeMaker 的工具配置弹窗，用户可对每个 Agent 工具单独设置 Auto/开启/关闭。

**工具配置项**：
```
仓库文件读取    [Auto ▾]
Plan Mode      [关闭 ▾]
代码地图检索    [启用 ▾]
代码 Apply     [Auto ▾]
执行 CMD       [Auto ▾]
Glob 文件搜索   [启用 ▾]
Grep 内容搜索   [启用 ▾]
Memory 工具    [启用 ▾]
需求澄清工具    [启用 ▾]
MCP Server    [设置 →]
```

**Auto 含义**：AI 自行判断是否调用该工具。
**开启**：强制允许（无需 AI 决策）。
**关闭**：禁止 AI 调用该工具。

**文件清单**：
```
修改: settings/ForgeSettings.kt — 新增 toolConfig Map<String, ToolMode>
新增: toolwindow/ToolConfigPanel.kt — Swing 工具配置弹窗
修改: agent/AgentToolExecutor.kt — 执行前检查工具配置
修改: webui/js/chat.js — /tools 指令触发打开配置面板
```

---

### Step A6：Plan Mode（1.5 天）🔴 P0

**目标**：Agent 执行前先输出完整执行计划，用户确认后再逐步执行。

**执行流程**：
```
用户发送任务
    ↓
Agent 分析任务 → 生成执行计划（Markdown 格式）
    ↓
聊天面板展示计划：
  ┌────────────────────────────────────┐
  │ 📋 执行计划                         │
  │ 1. 读取 LlmService.kt 了解现有结构  │
  │ 2. 修改 chatStream() 添加超时控制   │
  │ 3. 新建 TimeoutConfig.kt 配置类    │
  │ 4. 更新 plugin.xml 注册新配置      │
  │                                    │
  │ [✅ 确认执行]  [✏️ 修改计划]  [✗ 取消] │
  └────────────────────────────────────┘
    ↓ 用户点击"确认执行"
Agent 按计划逐步执行工具调用
```

**实现要点**：
- Plan Mode 下，Agent system prompt 增加"先输出 `<plan>...</plan>` 再执行"指令
- `AgentService` 解析 `<plan>` 标签，暂停执行等待用户确认
- 用户"修改计划"时，将修改后的计划文本重新注入为 user 消息继续对话

**文件清单**：
```
修改: agent/AgentService.kt — Plan Mode 流程控制
修改: webui/index.html — 计划确认卡片 DOM
修改: webui/js/chat.js — renderPlanCard() + 确认/修改/取消逻辑
修改: toolwindow/ForgeChatPanel.kt — plan confirm/cancel JS Bridge
```

---

### Step A7：Inline Edit — 行内 AI 编辑（2 天）🔴 P0

**目标**：用户在编辑器中选中代码，按 `Alt+Enter` 或右键菜单触发行内 AI 编辑。

**交互流程**：
```
用户选中代码块
    ↓
按 Alt+A（或右键 → CodeForge: Edit with AI）
    ↓
弹出轻量输入框（在代码上方/下方）：
  ┌──────────────────────────────────┐
  │ 💬 描述修改意图...               │
  │ [优化性能] [添加注释] [修复Bug] [发送▶] │
  └──────────────────────────────────┘
    ↓ 用户输入并发送
AI 生成修改后代码
    ↓
InlineDiffManager 展示 diff，用户 Accept/Reject
```

**文件清单**：
```
新增: actions/InlineEditAction.kt — 注册 Alt+A 快捷键 Action
新增: toolwindow/InlineEditPopup.kt — 轻量弹出输入框（EditorPopupPanel）
修改: agent/AgentService.kt — 新增 runInlineEdit(selectedCode, instruction) 方法
修改: plugin.xml — 注册 InlineEditAction
```

---

### Step A8：错误自动修复循环（1 天）🔴 P0

**目标**：Agent 执行 build/test 命令失败后，自动进入修复循环直到成功或达到最大次数。

**执行逻辑**：
```
run_terminal("./gradlew build")
    ↓ 失败（exit code ≠ 0）
AI 读取错误输出 → 定位问题文件 → 修复代码
    ↓
再次 run_terminal("./gradlew build")
    ↓ 循环（最多 3 次）
成功 → 返回结果 / 仍失败 → 报告给用户
```

**文件清单**：
```
修改: agent/AgentService.kt — 新增 autoFixLoop 逻辑
修改: settings/ForgeSettings.kt — 新增 autoFixMaxRetries 配置项（默认 3）
```

---

### Step B1：一键生成 Project Rules（0.5 天）🟡 P1

**目标**：AI 自动分析项目代码结构，生成适配的 `.codeforge.md` 规则文件。

**触发方式**：
- `/rules` 指令 → 如无规则文件，显示"一键生成"按钮
- 设置页面 → "生成 Project Rules" 按钮

**AI 分析内容**：
- 项目语言/框架（通过 build.gradle/pom.xml/package.json 识别）
- 主要目录结构
- 已有代码风格（命名规范、注释语言等）

**文件清单**：
```
修改: util/ProjectRulesLoader.kt — 新增 generateRules(project) 方法
修改: toolwindow/ForgeChatPanel.kt — /rules 指令处理 + 一键生成
```

---

### Step B2：Memory 工具（1 天）🟡 P1

**目标**：AI 可以主动记忆用户的偏好、项目约定、历史决策，跨会话持久化。

**Memory 类型**：
- **用户偏好**：如"我偏好函数式写法"、"注释用中文"
- **项目约定**：如"该项目不使用 Lombok"、"测试类命名用 Test 后缀"
- **历史决策**：如"上次选择了 Redis 作为缓存方案"

**Agent 工具**：`save_memory(key, content)` / `read_memory(key)`

**存储**：JSON 序列化到 `ForgeSettings`，按项目隔离。

**文件清单**：
```
新增: agent/MemoryManager.kt — save/read/list/delete memory
修改: agent/AgentToolExecutor.kt — 注册 save_memory / read_memory 工具
修改: agent/AgentService.kt — system prompt 自动注入当前 Memory 内容
```

---

### Step B3：需求澄清工具（0.5 天）🟡 P1

**目标**：Agent 在执行复杂任务前，主动提问澄清需求，避免理解偏差导致返工。

**触发时机**：
- 工具配置中"需求澄清工具"设为启用时
- Agent 检测到任务描述模糊（通过 system prompt 引导）

**交互形式**：
```
用户: 帮我优化这个系统
    ↓
AI 澄清提问（以卡片形式展示）：
  ┌─────────────────────────────────┐
  │ 🤔 在开始之前，我需要了解：      │
  │                                 │
  │ 1. 优化的目标是什么？            │
  │    ○ 性能（响应速度）            │
  │    ○ 代码可读性                 │
  │    ○ 减少 Bug                   │
  │                                 │
  │ 2. 是否有不能修改的文件？        │
  │    [填写...                    ] │
  │                                 │
  │ [提交回答] [跳过直接执行]        │
  └─────────────────────────────────┘
```

**文件清单**：
```
修改: agent/AgentService.kt — 澄清问题解析 + 等待用户回答
修改: webui/js/chat.js — renderClarificationCard()
修改: toolwindow/ForgeChatPanel.kt — 澄清回答提交处理
```

---

### Step B4：Token 使用量实时显示（0.5 天）🟡 P1

**目标**：在聊天面板底部实时显示当前会话的 Token 消耗（类 CodeMaker 底部 `Tokens: 99.7k`）。

**显示内容**：`Tokens: 输入 2.3k / 输出 1.1k / 总计 3.4k`

**文件清单**：
```
修改: service/LlmService.kt — 在 onDone 回调中携带 usage 信息
修改: toolwindow/ForgeChatPanel.kt — 解析 usage 推送给 Web UI
修改: webui/index.html + css/style.css — 底部 Token 显示区
修改: webui/js/chat.js — updateTokenCount()
```

---

### Step B5：消息反馈 + 重新生成（0.5 天）🟡 P1

**目标**：每条 AI 回复底部显示 👍 👎 反馈按钮和"重新生成"按钮。

**交互**：
- 👍 — 记录正面反馈（本地存储，未来可用于微调）
- 👎 — 弹出简短原因选择（不准确/不完整/格式问题/其他）
- 🔄 重新生成 — 使用相同输入重新调用 LLM，替换当前回复

**文件清单**：
```
修改: webui/js/chat.js — 消息底部操作栏渲染 + 重新生成逻辑
修改: toolwindow/ForgeChatPanel.kt — 反馈数据记录
```

---

### Step B6：自定义 Prompt 模板管理（1 天）🟡 P1

**目标**：内置常用 Prompt 模板，用户可自定义新增，通过 `/` 指令或 `+` 菜单快速使用。

**内置模板**：
- 解释代码 / 优化代码 / 重构代码
- 生成单元测试 / 生成文档注释
- 查找 Bug / 代码安全审查
- 翻译注释（中↔英）

**文件清单**：
```
新增: settings/PromptTemplateManager.kt — 模板 CRUD
新增: toolwindow/PromptTemplatePanel.kt — 模板管理 Swing 面板
修改: webui/js/chat.js — 模板选择集成到 / 指令列表
```

---

### Step B7：Tab 代码补全质量优化（1 天）🟡 P1

**目标**：提升现有 `ForgeInlineCompletionProvider` 的补全质量和体验。

**优化点**：
- **FIM（Fill-in-the-Middle）格式**：使用 `<PRE>prefix<SUF>suffix<MID>` 标准格式，兼容 DeepSeek/CodeLlama FIM 模型
- **防抖优化**：从轮询改为协程 `withTimeout` + `cancel`，更准确
- **缓存**：相同前缀 300ms 内不重复请求
- **多行补全**：支持预测 3~5 行，Tab 接受单行，再次 Tab 接受下一行
- **语言过滤**：注释行、字符串内、import 行不触发补全

**文件清单**：
```
修改: completion/ForgeInlineCompletionProvider.kt — 全面优化
新增: completion/CompletionCache.kt — LRU 缓存
```

---

### Step B8：MCP Server 接入支持（1.5 天）🟡 P1

**目标**：支持 Model Context Protocol，允许用户配置外部 MCP 工具服务器。

**实现要点**：
- 在设置页配置 MCP Server URL（stdio 或 HTTP 模式）
- `AgentToolExecutor` 动态注册 MCP 工具到工具列表
- 工具配置面板显示已接入的 MCP Server 和工具列表

**文件清单**：
```
新增: agent/McpClient.kt — MCP 协议客户端（HTTP/stdio）
修改: agent/AgentToolExecutor.kt — 动态加载 MCP 工具
修改: settings/ForgeSettings.kt — MCP Server 配置
修改: toolwindow/ToolConfigPanel.kt — MCP 设置入口
```

---

### Step B9：代码 Hover 快速解释（0.5 天）🟡 P1

**目标**：鼠标悬停代码时，按住 `Alt` 键弹出 AI 解释气泡。

**文件清单**：
```
新增: actions/HoverExplainAction.kt — 注册 EditorMouseListener
修改: toolwindow/InlineEditPopup.kt — 复用弹出层组件显示解释结果
```

---

### Step C1：私有知识库（RAG）（2 天）🟢 P2

**目标**：用户上传私有文档（技术规范、API 文档、业务手册），建立本地向量知识库，通过 `@知识` 引用。

**技术方案**：
- 文档分块（Chunking）+ 嵌入向量（调用 Embedding API）
- 本地向量存储（SQLite + 余弦相似度，无需额外依赖）
- 检索时 Top-K 语义匹配，结果注入消息上下文

**文件清单**：
```
新增: knowledge/KnowledgeBaseManager.kt — 文档索引 + 检索
新增: knowledge/EmbeddingClient.kt — 调用 Embedding API
新增: toolwindow/KnowledgeBasePanel.kt — 知识库管理 UI
修改: toolwindow/ForgeChatPanel.kt — @知识 引用解析
```

---

### Step C2：网络搜索工具（1 天）🟢 P2

**目标**：Agent 可调用网络搜索（DuckDuckGo/Brave）获取最新信息。

**文件清单**：
```
新增: agent/tools/WebSearchTool.kt — HTTP 搜索 API 封装
修改: agent/AgentToolExecutor.kt — 注册 web_search 工具
修改: settings/ForgeSettings.kt — 搜索 API Key 配置
```

---

### Step C3：使用量统计 Dashboard（1 天）🟢 P2

**目标**：统计 Token 消耗、调用次数、费用估算，可视化展示。

**文件清单**：
```
新增: service/UsageTracker.kt — 使用量记录（本地 JSON）
新增: toolwindow/UsageDashboardPanel.kt — 统计图表 Swing 面板
修改: settings/ForgeSettingsConfigurable.kt — 添加 Dashboard 入口
```

---

### Step C4：智能模型路由（1 天）🟢 P2

**目标**：根据任务类型自动选择最优/最经济的模型。

**路由规则**（可配置）：
- 代码生成 → DeepSeek-Coder / Claude Sonnet
- 代码解释 → 轻量模型（GPT-4o-mini / DeepSeek-V3）
- 复杂规划 → Claude Opus / GPT-4o
- 快速问答 → 最快响应模型

**文件清单**：
```
新增: service/ModelRouter.kt — 路由规则引擎
修改: service/LlmService.kt — 接入 ModelRouter
修改: settings/ForgeSettings.kt — 路由规则配置
```

---

## 三、时间线总览

```
Week 1（4/16-4/18）— P0 核心体验
  Day 1 (4/16): A1 + 号菜单 + A2 图片附加
  Day 2 (4/17): A3 文档附加 + A4 Slash Commands
  Day 3 (4/18): A5 工具配置面板

Week 2（4/21-4/25）— P0 续 + P1 开始
  Day 4 (4/21): A6 Plan Mode
  Day 5 (4/22): A7 Inline Edit（上）
  Day 6 (4/23): A7 Inline Edit（下）+ A8 错误自动修复循环
  Day 7 (4/24): B1 一键生成 Rules + B2 Memory 工具
  Day 8 (4/25): B3 需求澄清 + B4 Token 显示 + B5 消息反馈

Week 3（4/28-5/2）— P1 续 + P2 开始
  Day 9  (4/28): B6 Prompt 模板管理
  Day 10 (4/29): B7 Tab 补全优化
  Day 11 (4/30): B8 MCP Server（上）
  Day 12 (5/1) : B8 MCP Server（下）+ B9 Hover 解释
  Day 13 (5/2) : C1 知识库 RAG（上）

Week 4（5/6-5/9）— P2 差异化
  Day 14 (5/6) : C1 知识库 RAG（下）
  Day 15 (5/7) : C2 网络搜索 + C3 统计 Dashboard
  Day 16 (5/8) : C4 智能模型路由 + 整体收尾测试
```

---

## 四、验收标准

### P0 必须达成
- [ ] 对话框 `+` 按钮弹出菜单，可选图片/文档/指令
- [ ] 粘贴/选择图片后显示附件预览，发送给 Vision 模型可正确识别
- [ ] 附加 .md/.txt 文档后，AI 回答可引用文档内容
- [ ] 输入 `/` 弹出指令列表，`/clear` 清空会话，`/rules` 打开规则
- [ ] 工具配置面板可开关每个 Agent 工具
- [ ] Plan Mode 下 Agent 先输出计划，用户确认后再执行
- [ ] 选中代码 → Alt+A → 行内编辑弹窗 → AI 修改 → InlineDiff 展示
- [ ] build 失败后 AI 自动读取错误并尝试修复（最多 3 次循环）

### P1 应该达成
- [ ] `/rules` 指令可触发 AI 一键生成 .codeforge.md
- [ ] Agent 可调用 save_memory / read_memory，跨会话记忆持久化
- [ ] 复杂任务执行前 AI 主动提问澄清
- [ ] 底部实时显示当前会话 Token 用量
- [ ] AI 回复底部有 👍👎 和"重新生成"按钮
- [ ] Prompt 模板面板可增删改，模板可通过 / 指令快速使用
- [ ] Tab 补全支持 FIM 格式，相同前缀 300ms 内不重复请求

### P2 加分项
- [ ] 私有文档上传后可通过 @知识 引用
- [ ] Agent 可调用 web_search 获取网络信息
- [ ] 使用量 Dashboard 显示本月 Token/费用统计
- [ ] 模型路由规则可配置，不同任务自动选模型
