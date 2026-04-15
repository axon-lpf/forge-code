# CodeForge Phase 2 — Task List

> 对标 Claude Code / CodeMaker，补齐核心功能缺口。
> 详细设计见：[dev-plan-phase2-agent-enhancement.md](dev-plan-phase2-agent-enhancement.md)
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
| P2-10 多模态输入 | 2 | 0 | ⬜ 未开始 |

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

- [ ] **T27** 改造 `chat.html` + `chat.js` — 输入框监听 `paste` 事件，检测到图片时显示缩略图预览+删除按钮，将图片转为 base64 通过 JS Bridge 传递给 Kotlin 侧；不支持 Vision 的 Provider 激活时隐藏图片输入区域
- [ ] **T28** 改造 `ForgeChatPanel.kt` — 新增 `handleImageInput(base64, mimeType)` JS Bridge 处理，将图片封装为 `{type: "image_url", image_url: {url: "data:image/...;base64,..."}}` 格式注入当前消息的 content 数组

---

## 开发规则

1. **每次只做一个 Task**，完成后在对应复选框打 `[x]`，更新进度总览
2. **Task 完成标准**：代码写完 + 编译通过 + 功能手动验证
3. **P0 优先**：T01 → T02 → T03 → T04 → T05 → T06 → T07 → T08 → T09
4. **不跨 Task 改动**：每个 Task 只改文件清单内的文件，避免引入预期外变更
5. **遇到阻塞**：在 Task 下方记录阻塞原因，跳到下一个可完成的 Task
