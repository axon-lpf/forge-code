# CodeForge Task List（Phase 2 + Phase 3）

> 详细设计见：
> - Phase 2：[dev-plan-phase2-agent-enhancement.md](dev-plan-phase2-agent-enhancement.md)
> - Phase 3：[dev-plan-phase3.md](dev-plan-phase3.md)
> 更新时间：2026-04-15

---

## 进度总览

| 模块 | 任务数 | 完成 | 状态 |
|------|--------|------|------|
| P0-1 run_terminal 修复 | 2 | 2 | ✅ 已完成 |
| P0-2 多文件修改队列 | 4 | 4 | ✅ 已完成 |
| P0-3 上下文自动注入 | 3 | 3 | ✅ 已完成 |
| P1-4 Agent 步骤可视化 | 4 | 4 | ✅ 已完成 |
| P1-5 项目规则文件 | 2 | 2 | ✅ 已完成 |
| P1-6 Git 深度集成 | 3 | 3 | ✅ 已完成 |
| P1-7 多会话管理 UI | 3 | 3 | ✅ 已完成 |
| P1-8 @Codebase 检索 | 3 | 3 | ✅ 已完成 |
| P2-9 Checkpoint 回滚 | 2 | 2 | ✅ 已完成 |
| P2-10 多模态输入 | 2 | 2 | ✅ 完成（合并至 A2） |

---

## 🔴 P0-1：修复 run_terminal — 真实执行 + 输出捕获

> 文件：`agent/AgentToolExecutor.kt`
> 当前问题：只激活 Terminal 面板，命令不执行、输出不捕获，Agent 80% 能力形同虚设。

- [x] **T01** 重写 `runTerminal()` — 用 `ProcessBuilder` 真实执行命令，跨平台适配（Windows: `cmd /c`，Unix: `bash -c`）
- [x] **T02** 执行前弹出用户确认对话框（`Messages.showYesNoDialog`），危险命令黑名单直接拒绝，捕获 stdout+stderr，超时 60s 自动终止，将完整输出作为 `ToolResult` 返回给 LLM

---

## 🔴 P0-2：多文件修改队列 + 统一 Accept/Reject

> 当前问题：Agent 每次 `write_file` 立即弹 InlineDiff，5 个文件 = 5 次打断。
> 目标：Agent 完成后统一展示所有变更，用户一次性确认。

- [x] **T03** 新建 `diff/MultiFileDiffManager.kt` — 定义 `FilePatch`、`MultiFileDiffSession` 数据类，实现 `beginSession` / `addPatch` / `acceptAll` / `rejectAll` / `acceptFile` / `rejectFile` 方法
- [x] **T04** 新建 `diff/MultiFileDiffPanel.kt` — Swing 面板，展示文件变更列表（文件名、状态、+/-行数、单文件接受/拒绝按钮），顶部"接受全部 / 拒绝全部"按钮，点击文件名触发 `InlineDiffManager`
- [x] **T05** 改造 `AgentToolExecutor.writeFile()` — 从"立即展示 InlineDiff"改为"收集到当前 session 的 patch 列表"（不立即打开文件）
- [x] **T06** 改造 `AgentService.runAgent()` — 任务开始时调用 `beginSession`，`onDone` 回调中调用 `showReviewPanel`；将 taskId 传递给 `writeFile`

---

## 🔴 P0-3：当前文件上下文自动注入

> 当前问题：Agent system prompt 只有项目文件树（文件名），AI 不知道用户当前在看哪个文件、光标在哪。

- [x] **T07** 增强 `EditorContextProvider.kt` — 新增 PSI 解析（反射获取光标所在类名/方法名，支持 Java/Kotlin/Go/Python），`formatAsPrompt` 展示 `ClassName > methodName（第N行）`
- [x] **T08** 改造 `AgentService.runAgent()` — system prompt 新增"用户当前编辑器状态"节（调用 `EditorContextProvider.getFormattedContext`），AI 优先参考该上下文
- [x] **T09** 确认 `ForgeChatPanel.kt` — `handleSendMessage` 和 `handleRunAgent` 已注入编辑器上下文（已存在，无需改动）

---

## 🟡 P1-4：Agent 步骤可视化 — 工具调用卡片

> 当前问题：Agent 执行过程对用户完全不透明，只有"思考中..."转圈。

- [x] **T10** 改造 `AgentService.kt` — THINKING 阶段将 LLM 生成的每个 token 实时通过 `executeJS("window.appendThinkingToken(token)")` 推送到 Web UI；TOOL_CALL 步骤执行后通过 `executeJS("window.addToolCallCard(json)")` 推送卡片数据
- [x] **T11** 改造 `chat.html`/`style.css` — 添加工具调用卡片的 CSS 样式（卡片背景、图标、折叠动画 max-height transition、running 脉冲边框、success/error 状态色）
- [x] **T12** 改造 `chat.js` — 实现 `window.appendThinkingToken(token)`（流式追加到 thinking 气泡，过滤 tool_call XML）、`window.addToolCallCard(stepJson)`（渲染工具卡片，支持展开/折叠）、`window.finalizeAgentPanel` 对外暴露
- [x] **T13** 改造 `ForgeChatPanel.kt` — 扩展 JS Bridge 协议，新增 `AGENT_STEP_TOKEN` 和 `AGENT_TOOL_CARD` 消息类型处理；onDone/onError 时调用 `window.finalizeAgentPanel` 确保面板状态正确

---

## 🟡 P1-5：项目规则文件 .codeforge.md

> 类似 Cursor 的 `.cursorrules`，定义项目级 AI 行为规则，自动注入 system prompt。

- [x] **T14** 新建 `util/ProjectRulesLoader.kt` — 按优先级查找并读取规则文件（项目根 `.codeforge.md` → `~/.codeforge/global.md`），注册 `VirtualFileListener` 监听文件变更自动重载，内容长度限制 4000 字符
- [x] **T15** 改造 `AgentService.kt` 和 `ForgeChatPanel.kt` — 在 system prompt 头部注入 `ProjectRulesLoader.load(project)` 的规则内容；在聊天面板顶部显示规则文件激活标识（如 "📋 .codeforge.md 已加载"）

---

## 🟡 P1-6：Git 深度集成

> 在现有 `ReviewService`（git diff 审查）基础上新增 Commit Message 生成、PR 描述生成。

- [x] **T16** 新建 `actions/GitActions.kt` — 实现 `GenerateCommitMessageAction`（获取 staged diff → LLM 生成 conventional commit 格式 → 弹出可编辑对话框 → 执行 `git commit -m`）和 `GeneratePRDescriptionAction`（获取 branch diff → LLM 生成 PR 描述 → 复制到剪贴板）
- [x] **T17** 改造 `review/ReviewService.kt` — 新增 `getBranchDiff(project, baseBranch)` 方法，用于获取当前分支与目标分支的完整 diff；新增 `generateCommitMessage(diff, onToken, onDone, onError)` 专用 Prompt
- [x] **T18** 改造 `plugin.xml` — 注册 `GenerateCommitMessageAction`（加入 `Vcs.MessageActionGroup`）和 `GeneratePRDescriptionAction`

---

## 🟡 P1-7：多会话管理 UI 完善

> 当前问题：会话管理代码框架已有，但 Web UI 不完整，无侧边栏历史列表。

- [x] **T19** 改造 `chat.html` + `chat.js` — 实现左侧会话历史侧边栏（按今天/昨天/更早分组，显示会话标题+时间戳+模式图标），支持点击切换会话、右键重命名/删除，顶部搜索框按标题/内容检索
- [x] **T20** 改造 `ForgeChatPanel.kt` — 实现会话持久化（JSON 序列化到 `ForgeSettings`），会话数量上限（默认 50 条 FIFO），第一条消息后异步调用 LLM 生成 3~5 字会话标题
- [x] **T21** 改造 `chat.js` — 实现会话导出（Markdown 格式），通过 JS Bridge 调用 Kotlin 侧文件写入；侧边栏可折叠（默认.expand，小屏幕自动收起）

---

## 🟡 P1-8：@Codebase 代码库检索增强

> 当前问题：`search_code` 只是字符串 grep，无法语义搜索；聊天框无 @ 引用功能。

- [x] **T22** 新建 `util/CodebaseSearcher.kt` — 基于 IntelliJ PSI API 实现 `searchByName(project, query)`（类名/方法名模糊匹配）、`findImplementations(project, interfaceName)`、`findUsages(project, className, methodName)`；Java/Kotlin 优先，其他语言降级为 grep
- [x] **T23** 改造 `AgentToolExecutor.searchCode()` — 优先调用 `CodebaseSearcher.searchByName`，PSI 不可用时降级为原有 grep 实现
- [x] **T24** 改造 `chat.js` + `ForgeChatPanel.kt` — 输入框输入 `@` 时弹出文件/类搜索下拉列表（调用 Kotlin 侧搜索），选择后插入 `@文件名` 标记；`ForgeChatPanel` 发送消息前解析 `@引用`，自动读取对应文件内容并附加到消息上下文

---

## 🟢 P2-9：Checkpoint / 修改回滚

> Agent 任务开始前自动快照，支持一键回滚。

- [x] **T25** 新建 `agent/CheckpointManager.kt` — 定义 `Checkpoint` 数据类（id、timestamp、taskDescription、snapshots），实现 `createCheckpoint`（读取即将被修改的文件内容）、`restore`（批量写回文件）、`getAll`、`clear`，最多保留 10 个
- [x] **T26** 改造 `AgentService.runAgent()` — 任务开始前调用 `CheckpointManager.createCheckpoint`；在 `chat.js` 中展示 Checkpoint 时间线（底部小条，点击可回滚）

---

## 🟢 P2-10：多模态输入（图片/截图）

> 支持 Ctrl+V 粘贴截图到聊天框，发送给 Vision 模型分析。

- [x] **T27** 改造 `chat.html` + `chat.js` — 输入框监听 `paste` 事件，检测到图片时显示缩略图预览+删除按钮，将图片转为 base64 通过 JS Bridge 传递给 Kotlin 侧；不支持 Vision 的 Provider 激活时隐藏图片输入区域
- [x] **T28** 改造 `ForgeChatPanel.kt` — 新增 `handleImageInput(base64, mimeType)` JS Bridge 处理，将图片封装为 `{type: "image_url", image_url: {url: "data:image/...;base64,..."}}` 格式注入当前消息的 content 数组

---

## 开发规则

1. **每次只做一个 Task**，完成后在对应复选框打 `[x]`，更新进度总览
2. **Task 完成标准**：代码写完 + 编译通过 + 功能手动验证
3. **P0 优先**：T01 → T02 → T03 → T04 → T05 → T06 → T07 → T08 → T09
4. **不跨 Task 改动**：每个 Task 只改文件清单内的文件，避免引入预期外变更
5. **遇到阻塞**：在 Task 下方记录阻塞原因，跳到下一个可完成的 Task

---

---

# Phase 3 — 对标 CodeMaker / Claude Code 全功能补齐

> 详细设计见：[dev-plan-phase3.md](dev-plan-phase3.md)
> 开始日期：2026-04-16 ｜ 预计工期：16 个工作日

## Phase 3 进度总览

| 模块 | 任务数 | 完成 | 状态 |
|------|--------|------|------|
| P0-A1 + 号菜单 | 1 | 1 | ✅ 完成 |
| P0-A2 图片附加（含 P2-10）| 2 | 2 | ✅ 完成 |
| P0-A3 文档附加 | 2 | 2 | ✅ 完成 |
| P0-A4 Slash Commands | 2 | 2 | ✅ 完成 |
| P0-A5 工具配置面板 | 2 | 2 | ✅ 完成 |
| P0-A6 Plan Mode | 2 | 2 | ✅ 完成 |
| P0-A7 Inline Edit | 3 | 3 | ✅ 完成 |
| P0-A8 错误自动修复循环 | 2 | 2 | ✅ 完成 |
| P1-B1 一键生成 Rules | 1 | 1 | ✅ 完成 |
| P1-B2 Memory 工具 | 3 | 3 | ✅ 完成 |
| P1-B3 需求澄清工具 | 2 | 2 | ✅ 完成 |
| P1-B4 Token 用量显示 | 3 | 1 | 🔄 部分完成 |
| P1-B5 消息反馈 + 重新生成 | 2 | 2 | ✅ 完成 |
| P1-B6 Prompt 模板管理 | 2 | 2 | ✅ 完成 |
| P1-B7 Tab 补全优化 | 2 | 2 | ✅ 完成 |
| P1-B8 MCP Server | 3 | 1 | 🔄 部分完成 |
| P1-B9 Hover 快速解释 | 2 | 1 | 🔄 部分完成 |
| P2-C1 私有知识库 RAG | 3 | 0 | ⬜ 未开始 |
| P2-C2 网络搜索工具 | 2 | 0 | ⬜ 未开始 |
| P2-C3 使用量统计 Dashboard | 2 | 0 | ⬜ 未开始 |
| P2-C4 智能模型路由 | 2 | 0 | ⬜ 未开始 |

---

## 🔴 P0-A1：对话框 + 号菜单

> 在输入框左下角添加 `+` 按钮，展开包含图片/文档/指令选项的菜单。

- [x] **TA01** 改造 `index.html` + `style.css` + `chat.js` — 添加 `+` 按钮及弹出菜单 DOM，菜单含"🖼 图片 / 📄 文档 / / 指令"三项；菜单样式（弹出动画、hover 高亮）；`+` 点击展开/关闭逻辑，点击外部自动关闭

---

## 🔴 P0-A2：图片附加完整实现（合并 P2-10）

> Ctrl+V 粘贴 + 点击选择图片，显示顶部附件预览区，支持 Vision 模型发送。

- [x] **TA02** 改造 `chat.js` + `index.html` + `style.css` — 输入框监听 `paste` 事件检测图片，`+` 菜单点击"图片"调用 Kotlin 文件选择对话框；顶部附件预览区显示缩略图（最多 5 张），每张可单独删除（✕）；不支持 Vision 的模型时图片选项置灰
- [x] **TA03** 改造 `ForgeChatPanel.kt` — 新增 `handleImageInput(base64, mimeType)` 处理粘贴图片；新增 `handleOpenImagePicker()` 调用 `JFileChooser` 弹出文件选择；发送时将所有 pendingImages 封装为 `image_url` 格式注入消息 content 数组

---

## 🔴 P0-A3：文档附加

> 通过 `+` 菜单附加本地 .md/.txt/.pdf 等文档，内容自动注入消息上下文。

- [x] **TA04** 新建 `util/DocumentExtractor.kt` — 实现 `extract(filePath): String`，支持 `.md/.txt/.log` 直接读取，`.pdf` 使用提示文本，代码文件直接读取；内容超 8000 字符截断并提示
- [x] **TA05** 改造 `ForgeChatPanel.kt` + `chat.js` — 新增 `handleOpenDocumentPicker()` 调用文件选择对话框；文档内容以 `[附件: filename]\n内容\n[/附件]` 格式注入消息；附件预览区显示文档文件名图标

---

## 🔴 P0-A4：/ Slash Commands 系统

> 输入 `/` 时弹出指令选择面板，方向键选择，Enter 执行。

- [x] **TA06** 改造 `index.html` + `style.css` — 新增 Slash 指令浮动面板 DOM（绝对定位，输入框上方），面板含指令列表（图标+名称+描述），支持模糊过滤高亮
- [x] **TA07** 改造 `chat.js` + `ForgeChatPanel.kt` — 输入框监听 `/` 触发，实时过滤匹配指令列表；方向键上下移动高亮，Enter/点击执行，ESC 关闭；内置指令：`/clear` 清空、`/help` 帮助、`/rules` 规则、`/checkpoint` 回滚、`/export` 导出、`/model` 切换模型、`/review` 审查、`/commit` 提交、`/plan` 规划模式、`/tools` 工具配置

---

## 🔴 P0-A5：工具配置面板

> 类似 CodeMaker 工具配置弹窗，用户可对每个 Agent 工具单独设置 Auto/开启/关闭。

- [x] **TA08** 改造 `settings/ForgeSettings.kt` — 新增 `toolConfig: Map<String, ToolMode>`（ToolMode = AUTO/ENABLED/DISABLED），持久化每个工具的开关状态；新增默认配置
- [x] **TA09** 新建 `toolwindow/ToolConfigPanel.kt` — Swing 弹窗，列出所有工具（仓库读取/Plan Mode/代码检索/代码Apply/执行CMD/Glob/Grep/Memory/需求澄清/MCP），每行显示工具名+说明+下拉选择器（Auto/开启/关闭）；"保存"写入 ForgeSettings；改造 `AgentToolExecutor` 执行前检查工具配置；`/tools` 指令通过 JS Bridge 触发打开

---

## 🔴 P0-A6：Plan Mode — 先规划后执行

> Agent 执行前先输出执行计划，用户确认后再逐步执行工具调用。

- [x] **TA10** 改造 `agent/AgentService.kt` — Plan Mode 开启时在 system prompt 增加"先输出 `<plan>...</plan>` 标签内的执行计划，等待用户确认后再执行工具"；解析 LLM 输出中的 `<plan>` 标签，暂停执行并等待 JS Bridge 返回 `plan_confirm` / `plan_cancel` / `plan_modify` 消息
- [x] **TA11** 改造 `chat.js` + `index.html` + `ForgeChatPanel.kt` — 新增 `renderPlanCard(planText)` 渲染执行计划卡片（Markdown 格式，含编号步骤列表）；卡片底部三个按钮：✅ 确认执行 / ✏️ 修改计划 / ✗ 取消；"修改计划"弹出可编辑文本框，提交后将修改内容作为新 user 消息继续对话

---

## 🔴 P0-A7：Inline Edit — 行内 AI 编辑

> 选中代码 → Alt+A / 右键菜单 → 行内弹窗输入修改意图 → InlineDiff 展示结果。

- [x] **TA12** 新建 `actions/InlineEditAction.kt` — 注册 `Alt+A` 快捷键 Action（EditorAction），获取当前选中代码（`editor.selectionModel`）；通过 `plugin.xml` 注册，并加入右键菜单 `EditorPopupMenu` group
- [x] **TA13** 新建 `toolwindow/InlineEditPopup.kt` — 在选中代码上方弹出轻量输入框（`JBPopup` / `EditorPopupPanel`），含文本输入区和快捷按钮（优化性能/添加注释/修复Bug/生成测试）；用户提交后调用 AgentService
- [x] **TA14** 改造 `agent/AgentService.kt` — 新增 `runInlineEdit(project, selectedCode, instruction, onDone)` 方法；构建 prompt（将选中代码 + 用户指令发给 LLM，要求只返回修改后代码）；收到结果后调用 `InlineDiffManager` 展示 diff，用户 Accept/Reject

---

## 🔴 P0-A8：错误自动修复循环

> build/test 命令失败时，AI 自动读取错误输出并修复，循环直到成功或达到最大次数。

- [x] **TA15** 改造 `agent/AgentService.kt` — `runTerminal` 结果为失败（exit code ≠ 0）时，自动进入修复循环：将错误输出附加到对话历史，重新调用 LLM 请求修复，再次执行相同命令；循环次数受 `autoFixMaxRetries` 限制（默认 3）；每次循环在聊天面板显示"🔄 第 N 次自动修复尝试..."
- [x] **TA16** 改造 `settings/ForgeSettings.kt` — 新增 `autoFixEnabled: Boolean`（默认 true）、`autoFixMaxRetries: Int`（默认 3）配置项，在设置页面显示

---

## 🟡 P1-B1：一键生成 Project Rules

> AI 分析项目代码结构，自动生成适配的 `.codeforge.md` 规则文件。

- [ ] **TB01** 改造 `util/ProjectRulesLoader.kt` + `ForgeChatPanel.kt` — 新增 `generateRules(project): String` 方法，收集项目信息（build.gradle/pom.xml/package.json 识别技术栈，顶层目录结构，已有 .codeforge.md 内容）调用 LLM 生成规则；`/rules` 指令处理：若无规则文件显示"一键生成"按钮，点击触发生成并写入 `.codeforge.md`，生成后自动重载并显示激活标识

---

## 🟡 P1-B2：Memory 工具

> AI 可跨会话记忆用户偏好、项目约定、历史决策，持久化存储。

- [ ] **TB02** 新建 `agent/MemoryManager.kt` — 定义 `MemoryEntry(key, content, timestamp, projectPath)`，实现 `save(key, content)`、`read(key): String?`、`listAll(): List<MemoryEntry>`、`delete(key)`；存储为 JSON 序列化到 `ForgeSettings`，按 projectPath 隔离；最多保留 100 条
- [ ] **TB03** 改造 `agent/AgentToolExecutor.kt` — 注册 `save_memory` 工具（参数: key, content）和 `read_memory` 工具（参数: key）；工具描述清晰说明使用场景
- [ ] **TB04** 改造 `agent/AgentService.kt` — system prompt 尾部自动注入当前项目的所有 Memory 条目（格式：`== 记忆 ==\n- key: content\n...`），让 AI 自动感知已有记忆

---

## 🟡 P1-B3：需求澄清工具

> Agent 在执行复杂/模糊任务前，主动提问澄清需求，避免理解偏差。

- [ ] **TB05** 改造 `agent/AgentService.kt` — 需求澄清工具启用时，system prompt 增加"如任务描述模糊，先输出 `<clarify>问题列表</clarify>` 请用户澄清，再执行"；解析 `<clarify>` 标签，暂停执行等待用户回答（通过 `clarify_answer` JS Bridge 消息接收）
- [ ] **TB06** 改造 `chat.js` + `ForgeChatPanel.kt` — 新增 `renderClarificationCard(questions)` 渲染澄清卡片（问题列表 + 输入框/单选/多选 + "提交回答"/"跳过直接执行"按钮）；用户提交答案后将回答注入对话继续执行

---

## 🟡 P1-B4：Token 使用量实时显示

> 聊天面板底部实时显示当前会话 Token 消耗，类 CodeMaker 底部 `Tokens: 99.7k`。

- [ ] **TB07** 改造 `service/LlmService.kt` — 解析 LLM API 响应中的 `usage` 字段（`prompt_tokens`, `completion_tokens`）；在 `onDone` 回调参数中携带 usage 信息（或通过独立回调 `onUsage(promptTokens, completionTokens)`）
- [ ] **TB08** 改造 `toolwindow/ForgeChatPanel.kt` — 接收 usage 信息后调用 `executeJS("window.updateTokenCount(input, output)")` 推送到 Web UI；累计当前会话总 token
- [ ] **TB09** 改造 `index.html` + `style.css` + `chat.js` — 底部 bar 右侧新增 Token 显示区（`Tokens: 2.3k + 1.1k = 3.4k`，数字格式化为 k/M）；新会话时重置计数

---

## 🟡 P1-B5：消息反馈 + 重新生成

> 每条 AI 回复底部显示 👍 👎 反馈按钮和 🔄 重新生成按钮。

- [ ] **TB10** 改造 `chat.js` — AI 消息气泡渲染后，底部追加操作栏（👍 / 👎 / 🔄 重新生成）；👍👎 点击后变色并记录反馈（localStorage）；👎 点击弹出原因浮层（不准确/不完整/格式问题/其他）；🔄 重新生成：隐藏当前 AI 消息，使用相同用户消息重新调用 LLM 并替换
- [ ] **TB11** 改造 `ForgeChatPanel.kt` — 新增 `handleRegenerate(messageIndex)` JS Bridge 处理，重新发送对应用户消息；反馈数据通过 `handleFeedback(messageId, type, reason)` 存储到 ForgeSettings

---

## 🟡 P1-B6：自定义 Prompt 模板管理

> 内置常用 Prompt 模板 + 用户自定义，通过 / 指令快速使用。

- [x] **TB12** 新建 `settings/PromptTemplateManager.kt` — 定义 `PromptTemplate(id, name, icon, prompt, isBuiltin)`；内置模板（解释代码/优化代码/重构代码/生成单元测试/生成文档注释/查找Bug/代码安全审查/翻译注释）；用户自定义模板 CRUD，序列化到 ForgeSettings
- [x] **TB13** 新建 `toolwindow/PromptTemplatePanel.kt` — Swing 面板，左侧模板列表（内置+自定义分组），右侧编辑区（名称/图标/Prompt 内容），支持新增/编辑/删除自定义模板；改造 `chat.js` 将模板集成到 `/` 指令列表（`/explain` `/optimize` `/test` 等）

---

## 🟡 P1-B7：Tab 代码补全质量优化

> 全面优化现有 `ForgeInlineCompletionProvider`，提升补全质量和体验。

- [ ] **TB14** 改造 `completion/ForgeInlineCompletionProvider.kt` — 改用 FIM（Fill-in-the-Middle）格式 Prompt（`<PRE>prefix<SUF>suffix<MID>`）；防抖改为协程 `withTimeout` + job cancel 机制；过滤注释行/字符串内/import 行不触发；增加语言类型检测，针对不同语言调整 Prompt
- [ ] **TB15** 新建 `completion/CompletionCache.kt` — LRU 缓存（最大 50 条，key=前缀哈希），300ms TTL；相同前缀命中缓存直接返回，避免重复请求；缓存命中率统计（debug 日志）

---

## 🟡 P1-B8：MCP Server 接入支持

> 支持 Model Context Protocol，允许用户配置外部 MCP 工具服务器。

- [x] **TB16** 新建 `agent/McpClient.kt` — 实现 MCP 协议客户端（HTTP SSE 模式优先，stdio 模式备选）；`listTools(): List<McpTool>` 获取 MCP 服务提供的工具列表；`callTool(name, args): String` 调用工具并返回结果
- [x] **TB17** 改造 `agent/AgentToolExecutor.kt` — 启动时调用 `McpClient.listTools()` 动态注册 MCP 工具到工具列表；LLM 调用对应工具时转发给 `McpClient.callTool()`；工具定义（name/description/parameters）完整透传给 LLM
- [ ] **TB18** 改造 `settings/ForgeSettings.kt` + `toolwindow/ToolConfigPanel.kt` — 新增 MCP Server 配置列表（URL/名称/类型）；工具配置面板底部显示"MCP Server →"入口，点击打开 MCP 配置弹窗，显示已连接的工具列表

---

## 🟡 P1-B9：代码 Hover 快速解释

> 鼠标悬停代码时，按住 Alt 键弹出 AI 解释气泡。

- [x] **TB19** 新建 `actions/HoverExplainAction.kt` — 注册 `EditorMouseMotionListener`，检测鼠标悬停 + Alt 键按下；获取悬停位置的 token/表达式（通过 `editor.document.getText(range)`）；防抖 800ms 后调用 LLM 生成解释
- [x] **TB20** 改造 `toolwindow/InlineEditPopup.kt`（或新建 `HoverExplainPopup.kt`）— 复用轻量弹窗组件，在鼠标位置附近显示解释气泡（流式输出）；鼠标移开后自动消失

---

## 🟢 P2-C1：私有知识库（RAG）

> 用户上传私有文档建立本地向量知识库，通过 `@知识` 引用检索。

- [ ] **TC01** 新建 `knowledge/KnowledgeBaseManager.kt` — 文档分块（Chunking，每块 512 token）；调用 Embedding API 获取向量；本地存储（SQLite WAL 模式，表：chunks/embeddings）；`search(query, topK=5)` 余弦相似度检索返回最相关块
- [ ] **TC02** 新建 `knowledge/EmbeddingClient.kt` — 封装 Embedding API 调用（复用 LlmService 的 API Key + BaseURL）；支持 OpenAI `text-embedding-3-small`、通义 `text-embedding-v3` 等
- [ ] **TC03** 新建 `toolwindow/KnowledgeBasePanel.kt` + 改造 `ForgeChatPanel.kt` — 知识库管理 Swing 面板（上传文档/查看已索引文档/删除）；`@知识` 引用时调用 `KnowledgeBaseManager.search()` 检索并注入上下文；Agent 新增 `search_knowledge(query)` 工具

---

## 🟢 P2-C2：网络搜索工具

> Agent 可调用网络搜索获取最新信息（DuckDuckGo / Brave Search API）。

- [ ] **TC04** 新建 `agent/tools/WebSearchTool.kt` — 封装 DuckDuckGo Instant Answer API（免费无需 Key）或 Brave Search API（需 Key）；`search(query, maxResults=5): List<SearchResult>`；结果格式化为 `标题\nURL\n摘要` 返回给 LLM
- [ ] **TC05** 改造 `agent/AgentToolExecutor.kt` + `settings/ForgeSettings.kt` — 注册 `web_search(query)` 工具；设置页新增搜索引擎选择（DuckDuckGo 免费 / Brave API Key 输入）；工具配置面板显示网络搜索开关

---

## 🟢 P2-C3：使用量统计 Dashboard

> 统计 Token 消耗、调用次数、费用估算，Swing 面板可视化展示。

- [ ] **TC06** 新建 `service/UsageTracker.kt` — 每次 LLM 调用后记录（timestamp/model/promptTokens/completionTokens/provider）；序列化到本地 JSON 文件（`~/.codeforge/usage.json`）；提供按日/周/月聚合查询
- [ ] **TC07** 新建 `toolwindow/UsageDashboardPanel.kt` + 改造 `ForgeSettingsConfigurable.kt` — Dashboard Swing 面板：今日/本周/本月 token 用量卡片，各模型使用占比（JFreeChart 或纯 Swing 绘图），费用估算（按主流模型定价）；设置页新增"使用统计"入口

---

## 🟢 P2-C4：智能模型路由

> 根据任务类型自动选择最优/最经济的模型，路由规则可配置。

- [ ] **TC08** 新建 `service/ModelRouter.kt` — 定义 `TaskType`（CODE_GENERATION / CODE_EXPLAIN / COMPLEX_PLANNING / QUICK_CHAT / INLINE_EDIT）；`route(taskType, userMessage): ModelConfig` 根据规则返回最优模型；规则可在设置页配置（任务类型 → 首选模型）
- [ ] **TC09** 改造 `service/LlmService.kt` + `settings/ForgeSettings.kt` — 智能路由开关（默认关闭）；开启时 `chatStream` / `runAgent` 等调用前先经过 `ModelRouter.route()` 确定模型；设置页"模型路由"面板展示和编辑路由规则表格
