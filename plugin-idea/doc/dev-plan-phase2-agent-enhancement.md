# CodeForge Phase 2 — 对标 Claude Code / CodeMaker 功能增强计划

> **目标**：对标 Anthropic Claude Code 和 CodeMaker 的核心产品能力，补齐关键功能缺口，打造真正可用的 AI 编程助手。
> **前置条件**：Phase 1（LLM Provider 整合）全部完成 ✅
> **预计工期**：10 个工作日
> **开始日期**：2026-04-16

---

## 一、竞品对标分析

### 1.1 Claude Code 核心能力对比

| 功能维度 | Claude Code | CodeForge 现状 | 差距等级 |
|----------|-------------|----------------|----------|
| 终端命令真实执行（捕获输出）| ✅ 完整支持 | ❌ 只激活面板，不执行 | 🔴 P0 |
| 多文件并行修改 + 统一确认 | ✅ 支持 | ❌ 每文件单独弹窗，阻断流 | 🔴 P0 |
| Agent 步骤可视化（思维链展示）| ✅ 可展开/折叠 | ⚠️ 只有"思考中..."占位 | 🟡 P1 |
| Checkpoint / 修改回滚 | ✅ 支持 | ❌ 无 | 🟡 P1 |
| 流式 write_file（边生成边预览）| ✅ 支持 | ❌ 模态对话框阻断流式 | 🟡 P1 |
| 图片 / 截图多模态输入 | ✅ 支持 | ❌ 无 | 🟢 P2 |

### 1.2 CodeMaker 核心能力对比

| 功能维度 | CodeMaker | CodeForge 现状 | 差距等级 |
|----------|-----------|----------------|----------|
| 当前文件上下文自动注入 | ✅ 光标所在类/方法引用链 | ⚠️ 只注入文件名，不含代码内容 | 🔴 P0 |
| @Codebase 语义搜索 | ✅ 向量化索引，语义级检索 | ⚠️ 只有文件名 grep | 🟡 P1 |
| 项目规则文件（.codeforge.md）| ✅ .cursorrules 全局/项目级 | ❌ 无 | 🟡 P1 |
| Git 集成（Commit / PR 描述）| ✅ 自动生成 Commit Message | ⚠️ 只有 diff 审查，无 Commit | 🟡 P1 |
| 多会话管理（侧边栏历史列表）| ✅ 完整会话历史浏览 | ⚠️ 功能框架有但 UI 不完善 | 🟡 P1 |
| 代码片段收藏夹 | ✅ 支持 | ❌ 无 | 🟢 P2 |
| 自定义 Prompt 模板管理 | ✅ 完整 UI | ❌ 只有单次输入弹窗 | 🟢 P2 |
| 图片/截图多模态输入 | ✅ 支持 | ❌ 无 | 🟢 P2 |

### 1.3 优先级汇总

```
🔴 P0（必须，阻塞核心使用体验）
  - run_terminal 真实执行 + 输出捕获
  - 多文件修改队列 + 统一 Accept/Reject
  - 当前文件上下文自动注入 Agent

🟡 P1（重要，显著提升使用体验）
  - Agent 步骤可视化（思维链面板）
  - 项目规则文件 .codeforge.md
  - Git 深度集成（Commit Message / PR 描述）
  - 多会话管理 UI 完善
  - @Codebase 代码库检索增强

🟢 P2（加分项，差异化竞争力）
  - Checkpoint / 修改回滚
  - 多模态输入（图片/截图）
  - 代码片段收藏夹
  - 自定义 Prompt 模板管理 UI
```

---

## 二、分步开发计划

### Step 1：修复 run_terminal — 真实执行 + 输出捕获（1 天）🔴 P0

**问题根因**：
当前 `AgentToolExecutor.runTerminal()` 只是激活 IDE Terminal 面板，根本没有把命令传递进去，也不捕获任何输出。LLM 拿不到命令执行结果，Agent 的编译/测试/安装依赖等能力全部形同虚设。

**目标**：
- 在系统进程中真实执行命令（ProcessBuilder）
- 捕获 stdout + stderr 输出流（实时流式）
- 将完整输出返回给 LLM 作为工具结果
- 超时保护（默认 60 秒）
- 危险命令黑名单校验（已有，需完善）
- 用户确认机制：执行前弹出确认对话框（可选，设置控制）

**实现方案**：

```kotlin
// AgentToolExecutor.kt — runTerminal() 重写
fun runTerminal(project: Project, command: String): ToolResult {
    // 1. 安全校验
    val dangerous = listOf("rm -rf /", "del /f /s", "format c:", "shutdown", "> /dev/sda")
    for (d in dangerous) {
        if (command.lowercase().contains(d.lowercase())) {
            return ToolResult("run_terminal", false, "", "⛔ 拒绝执行危险命令: $command")
        }
    }

    // 2. 用户确认（弹窗，可配置跳过）
    var confirmed = false
    ApplicationManager.getApplication().invokeAndWait {
        confirmed = Messages.showYesNoDialog(
            project,
            "Agent 请求执行以下命令：\n\n`$command`\n\n是否允许？",
            "CodeForge — 命令执行确认",
            "允许执行",
            "拒绝",
            Messages.getWarningIcon()
        ) == Messages.YES
    }
    if (!confirmed) return ToolResult("run_terminal", false, "", "用户拒绝执行命令")

    // 3. 真实执行
    return try {
        val workDir = project.basePath ?: System.getProperty("user.home")
        val isWindows = System.getProperty("os.name").lowercase().startsWith("win")
        val processCmd = if (isWindows) listOf("cmd", "/c", command)
                         else listOf("bash", "-c", command)

        val process = ProcessBuilder(processCmd)
            .directory(java.io.File(workDir))
            .redirectErrorStream(true)
            .start()

        // 4. 捕获输出（最多 60 秒，最多 8000 字符）
        val outputBuilder = StringBuilder()
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        val timeout = System.currentTimeMillis() + 60_000L

        while (System.currentTimeMillis() < timeout) {
            val line = reader.readLine() ?: break
            outputBuilder.appendLine(line)
            if (outputBuilder.length > 8000) {
                outputBuilder.append("\n... (输出过长，已截断)")
                break
            }
        }
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

        val exitCode = try { process.exitValue() } catch (e: Exception) { -1 }
        val output = outputBuilder.toString().trim()

        if (exitCode == 0) {
            ToolResult("run_terminal", true,
                "✅ 命令执行成功（exit code: 0）\n\n```\n$ $command\n${output.ifBlank { "(无输出)" }}\n```")
        } else {
            ToolResult("run_terminal", false,
                "❌ 命令执行失败（exit code: $exitCode）\n\n```\n$ $command\n$output\n```",
                "exit code: $exitCode")
        }
    } catch (e: Exception) {
        ToolResult("run_terminal", false, "", "命令执行异常: ${e.message}")
    }
}
```

**验证**：
- Agent 请求 `run_terminal: "ls -la"` → 弹出确认框 → 执行 → 返回真实目录列表
- Agent 请求 `run_terminal: "gradle build"` → 执行编译 → 返回构建结果
- Agent 请求 `run_terminal: "rm -rf /"` → 直接拒绝，不弹框

**文件清单**：
```
修改: src/main/kotlin/.../agent/AgentToolExecutor.kt（runTerminal 方法重写）
```

---

### Step 2：多文件修改队列 + 统一 Accept/Reject（2 天）🔴 P0

**问题根因**：
当前 `write_file` 工具每次调用都触发 `InlineDiffManager.showInlineDiff()`，立即打开文件并展示高亮。如果 Agent 要修改 5 个文件，用户面对的是 5 个依次弹出的对话框，完全打断 Agent 执行流。Claude Code 的做法是：**Agent 完成所有规划后，统一呈现所有文件变更，用户一次性确认**。

**目标**：
- 新建 `MultiFileDiffSession` 数据类，收集一次 Agent 任务中的所有 `write_file` 调用
- Agent 任务完成后，弹出多文件 Diff 面板（类似 IDE 的 "Version Control Changes" 视图）
- 用户可：全部接受 / 全部拒绝 / 逐文件接受/拒绝 / 按 hunk 接受/拒绝
- InlineDiffManager 和 CodeApplyManager 保留（用于单文件手动操作）

**核心数据结构**：

```kotlin
// 新建: src/main/kotlin/.../diff/MultiFileDiffManager.kt

object MultiFileDiffManager {

    /** 一次 Agent 任务的多文件变更批次 */
    data class FilePatch(
        val relativePath: String,      // 相对项目路径
        val originalContent: String,   // 原始内容（空字符串=新建文件）
        val newContent: String,        // AI 生成的新内容
        val isNewFile: Boolean = false // 是否是新建文件
    )

    data class MultiFileDiffSession(
        val taskId: String,            // Agent 任务 ID
        val patches: MutableList<FilePatch> = mutableListOf(),
        var completed: Boolean = false // Agent 是否已完成所有工具调用
    )

    private val pendingSessions = mutableMapOf<String, MultiFileDiffSession>()

    /** Agent 开始任务时创建批次 */
    fun beginSession(taskId: String): MultiFileDiffSession

    /** Agent 调用 write_file 时收集（不立即展示） */
    fun addPatch(taskId: String, patch: FilePatch)

    /** Agent 任务完成后，展示统一确认面板 */
    fun showReviewPanel(project: Project, taskId: String)

    /** 接受全部变更 */
    fun acceptAll(project: Project, taskId: String)

    /** 拒绝全部变更 */
    fun rejectAll(taskId: String)

    /** 接受单个文件变更 */
    fun acceptFile(project: Project, taskId: String, relativePath: String)

    /** 拒绝单个文件变更 */
    fun rejectFile(taskId: String, relativePath: String)
}
```

**多文件 Diff UI 面板**：
```
┌─────────────────────────────────────────────────────────────────┐
│  ⚡ CodeForge — Agent 完成任务，共修改 5 个文件                   │
│                                                                   │
│  [✅ 接受全部] [✗ 拒绝全部]                   [📋 查看差异]      │
├───────────────────────────────────────────────────────────────────┤
│  文件变更列表:                                                     │
│  ┌─────────────────────────────────┬──────┬───────┬────────────┐  │
│  │ 文件名                          │ 状态 │ 变更量 │ 操作       │  │
│  ├─────────────────────────────────┼──────┼───────┼────────────┤  │
│  │ 📝 src/main/.../LlmService.kt  │ 修改 │ +45 -12│ [接受][拒绝]│  │
│  │ 🆕 src/main/.../NewClass.kt    │ 新建 │ +120   │ [接受][拒绝]│  │
│  │ 📝 src/main/.../Config.kt      │ 修改 │ +8 -3  │ [接受][拒绝]│  │
│  │ 📝 build.gradle.kts            │ 修改 │ +2 -1  │ [接受][拒绝]│  │
│  │ 🆕 src/test/.../Test.kt        │ 新建 │ +65    │ [接受][拒绝]│  │
│  └─────────────────────────────────┴──────┴───────┴────────────┘  │
│                                                                   │
│  点击文件名查看内联 Diff（InlineDiffManager）                      │
└───────────────────────────────────────────────────────────────────┘
```

**对 AgentService 的改造**：
- `runAgent()` 开始时调用 `MultiFileDiffManager.beginSession(taskId)`
- `AgentToolExecutor.writeFile()` 改为：先收集到 session，**不立即展示**
- `runAgent()` 的 `onDone` 回调中调用 `MultiFileDiffManager.showReviewPanel(project, taskId)`

**文件清单**：
```
新增: src/main/kotlin/.../diff/MultiFileDiffManager.kt
新增: src/main/kotlin/.../diff/MultiFileDiffPanel.kt（Swing UI 面板）
修改: src/main/kotlin/.../agent/AgentService.kt（接入 MultiFileDiffManager）
修改: src/main/kotlin/.../agent/AgentToolExecutor.kt（write_file 改为收集模式）
```

---

### Step 3：当前文件上下文自动注入（0.5 天）🔴 P0

**问题根因**：
当前 Agent system prompt 中只注入了项目文件树（文件名列表），没有注入**当前打开文件的代码内容**和**光标所在类/方法**。用户在编辑某个文件时问 AI "帮我优化这个函数"，AI 根本不知道当前文件是什么。

**目标**：
- 每次 chat 消息发送时，自动注入当前编辑器上下文：
  - 当前文件名 + 完整代码（≤ 300 行）或选中区域
  - 当前光标所在的方法/类名（PSI 解析）
  - 最近打开的 3~5 个文件列表

**实现方案**：

```kotlin
// 新建: src/main/kotlin/.../util/ContextCollector.kt

object ContextCollector {

    data class EditorContext(
        val currentFile: String?,          // 当前文件相对路径
        val currentFileContent: String?,   // 当前文件内容（截断）
        val selectedCode: String?,         // 当前选中代码
        val cursorClass: String?,          // 光标所在类名（PSI）
        val cursorMethod: String?,         // 光标所在方法名（PSI）
        val recentFiles: List<String>      // 最近打开文件列表
    )

    /**
     * 收集当前编辑器上下文
     * 在 EDT 中调用
     */
    fun collect(project: Project): EditorContext

    /**
     * 将上下文格式化为注入 system prompt 的文本
     */
    fun formatForPrompt(ctx: EditorContext): String
}
```

**system prompt 注入格式**：
```
== 当前编辑器上下文 ==
当前文件：src/main/kotlin/com/codeforge/plugin/idea/service/LlmService.kt
光标位置：class LlmService > fun chatStream()

```kotlin
// 当前文件内容（前 200 行）：
package com.codeforge.plugin.idea.service
...
```

最近打开的文件：
- AgentService.kt
- AgentToolExecutor.kt
- ForgeChatPanel.kt
```

**对 AgentService 和 ForgeChatPanel 的改造**：
- `runAgent()` 的 system prompt 构建中，额外注入 `ContextCollector.collect(project)` 的结果
- `ForgeChatPanel` 在发送消息前（JS Bridge `handleSend`），采集并附加编辑器上下文

**文件清单**：
```
新增: src/main/kotlin/.../util/ContextCollector.kt
修改: src/main/kotlin/.../agent/AgentService.kt（system prompt 注入上下文）
修改: src/main/kotlin/.../toolwindow/ForgeChatPanel.kt（发送前采集上下文）
```

---

### Step 4：Agent 步骤可视化 — 思维链面板（1.5 天）🟡 P1

**问题根因**：
当前聊天面板（Web UI）只显示最终的 AI 回复，Agent 在执行工具调用过程中，用户看到的只是"AI 思考中..."的转圈动画，完全不知道 AI 正在做什么。Claude Code 的体验是：每个工具调用都显示为可展开的卡片，用户可以实时看到 AI 读了哪些文件、执行了什么命令、得到了什么结果。

**目标**：
- 每个 `TOOL_CALL` 步骤显示为可展开/折叠的卡片
- 卡片显示：工具名、参数（截断）、执行状态（running/success/error）、结果摘要
- 支持点击展开查看完整工具结果
- `THINKING` 状态显示实时 token 输出（流式打字效果，类似 Claude 的思维链）

**Web UI 改造（chat.html / chat.js）**：

```javascript
// 工具调用卡片渲染
function renderToolCallCard(step) {
    const icon = step.toolName === 'read_file' ? '📖' :
                 step.toolName === 'write_file' ? '✏️' :
                 step.toolName === 'run_terminal' ? '⚡' :
                 step.toolName === 'search_code' ? '🔍' :
                 step.toolName === 'list_files' ? '📁' : '🛠️';
    const status = step.content === 'success' ? '✅' : '❌';
    return `
    <div class="tool-call-card ${step.content}">
        <div class="tool-call-header" onclick="toggleCard(this)">
            ${icon} <b>${step.toolName}</b> ${status}
            <span class="tool-call-summary">${buildSummary(step)}</span>
            <span class="collapse-btn">▶</span>
        </div>
        <div class="tool-call-body" style="display:none">
            <pre>${escapeHtml(step.toolResult ?? '')}</pre>
        </div>
    </div>`;
}

// 思维链实时显示
function renderThinkingBlock(token) {
    // 在当前气泡中追加 token（流式效果）
    appendToThinkingBubble(token);
}
```

**Kotlin 侧改造（AgentService → Web UI 通信）**：
- 当前 `onStep(AgentStep(StepType.THINKING, ""))` 只是发空信号
- 改造：THINKING 阶段实时推送 LLM 生成的每个 token 到 Web UI
- TOOL_CALL 步骤：通过 `executeJS` 调用 `window.addToolCallCard(stepJson)` 渲染卡片

**文件清单**：
```
修改: src/main/resources/webui/chat.html（添加工具卡片 CSS）
修改: src/main/resources/webui/chat.js（工具卡片渲染逻辑）
修改: src/main/kotlin/.../agent/AgentService.kt（THINKING token 推送）
修改: src/main/kotlin/.../toolwindow/ForgeChatPanel.kt（扩展 JS Bridge 协议）
```

---

### Step 5：项目规则文件 .codeforge.md（0.5 天）🟡 P1

**功能描述**：
类似 Cursor 的 `.cursorrules`，允许用户在项目根目录创建 `.codeforge.md` 文件，定义项目级的 AI 行为规则。每次对话时，该文件内容自动注入到 system prompt 中。

**目标**：
- 检测项目根目录是否存在 `.codeforge.md`（或 `CODEFORGE.md`）
- 存在则自动读取并注入到所有对话的 system prompt 头部
- 文件变更时自动重新加载（FileWatcher）
- 全局规则：`~/.codeforge/global.md`（应用级设置）
- 在聊天面板显示当前激活的规则文件标识

**读取优先级**（高→低）：
1. 项目根目录 `.codeforge.md`
2. 用户 Home 目录 `~/.codeforge/global.md`
3. 插件内置默认规则

**典型 .codeforge.md 示例**：
```markdown
# 项目规则

## 技术栈
- 语言: Kotlin + Java
- 框架: IntelliJ Platform SDK
- 构建: Gradle Kotlin DSL

## 代码规范
- 所有注释使用中文
- 函数/类命名使用驼峰
- 不使用 Lombok（已去除依赖）

## AI 行为约定
- 优先修改现有文件，不随意新建
- 写入文件前必须先读取确认内容
- 所有 Kotlin 代码需要 KDoc 注释
```

**文件清单**：
```
新增: src/main/kotlin/.../util/ProjectRulesLoader.kt
修改: src/main/kotlin/.../agent/AgentService.kt（system prompt 注入项目规则）
修改: src/main/kotlin/.../toolwindow/ForgeChatPanel.kt（普通对话也注入规则）
```

---

### Step 6：Git 深度集成（1 天）🟡 P1

**功能描述**：
在现有 `ReviewService`（git diff 审查）基础上，新增：
1. **Commit Message 生成**：分析 staged changes，AI 生成符合规范的 commit 信息
2. **PR 描述生成**：分析 branch diff，生成完整的 PR/MR 描述
3. **Git 操作菜单**：在 VCS 菜单或 Git ToolWindow 中添加 CodeForge 入口

**Commit Message 生成流程**：
```
用户触发 "CodeForge: Generate Commit Message"
    ↓
ReviewService.getDiff(project, [], staged=true)（获取 staged changes）
    ↓
LLM 生成 conventional commit 格式的提交信息
（feat/fix/refactor/docs/test/chore: 描述）
    ↓
弹出编辑对话框，用户可修改后直接执行 git commit
    ↓
调用 git commit -m "xxx"（通过 run_terminal 或 IDE Git API）
```

**在 plugin.xml 注册的新 Actions**：
```xml
<action id="CodeForge.GenerateCommitMessage"
        class="com.codeforge.plugin.idea.actions.GitActions.GenerateCommitMessageAction"
        text="Generate Commit Message (AI)"
        description="用 AI 分析 staged changes 生成 commit 信息">
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="first"/>
</action>

<action id="CodeForge.GeneratePRDescription"
        class="com.codeforge.plugin.idea.actions.GitActions.GeneratePRDescriptionAction"
        text="Generate PR Description (AI)"
        description="用 AI 生成 PR/MR 描述">
    <add-to-group group-id="Git.Branches.Remote" anchor="first"/>
</action>
```

**文件清单**：
```
新增: src/main/kotlin/.../actions/GitActions.kt
修改: src/main/kotlin/.../review/ReviewService.kt（新增 PR diff 获取）
修改: src/main/resources/META-INF/plugin.xml（注册新 Actions）
```

---

### Step 7：多会话管理 UI 完善（1 天）🟡 P1

**问题根因**：
当前聊天面板虽然有会话管理的代码框架（`handleCreateSession`、`handleDeleteSession`、`handleRenameSession`），但 Web UI 实现不完整，用户体验与 Claude Code / CodeMaker 差距明显。

**目标**：
- 左侧会话列表（类似 Claude.ai 的侧边栏）：显示会话标题（AI 自动生成或用户命名）、时间戳、模式图标（Chat/Agent/Spec）
- 会话标题自动生成：用户第一条消息后，AI 自动生成 3~5 字的会话标题
- 会话搜索：支持按标题/内容关键词搜索历史会话
- 会话导出：导出为 Markdown 文件
- 会话数量上限：默认保留最近 50 条，可配置

**Web UI 改造（重点）**：

```
┌──────────────────────────────────────────────────────┐
│  ⚡ CodeForge          [+ 新会话]  [🔍 搜索]          │
├──────────┬───────────────────────────────────────────┤
│ 今天      │                                           │
│ > 优化LLM │          当前会话内容                      │
│   Service │                                           │
│ > Git集成 │                                           │
│   开发计划 │                                           │
│           │                                           │
│ 昨天      │                                           │
│ > IntelliJ│                                           │
│   插件调试 │                                           │
│           │                                           │
│ 更早      │                                           │
│ > Agent模式│                                          │
│   工具调用 │                                           │
└──────────┴───────────────────────────────────────────┘
```

**文件清单**：
```
修改: src/main/resources/webui/chat.html（会话列表 UI）
修改: src/main/resources/webui/chat.js（会话管理逻辑）
修改: src/main/kotlin/.../toolwindow/ForgeChatPanel.kt（会话持久化）
```

---

### Step 8：@Codebase 代码库检索增强（1.5 天）🟡 P1

**问题根因**：
当前 `AgentToolExecutor.searchCode()` 使用字符串匹配（`contains(keyword, ignoreCase = true)`），无法理解语义。例如用户问"处理用户登录的函数在哪"，无法通过关键词 grep 找到。

**目标**：
- 基于 IntelliJ PSI API 实现结构化代码搜索（搜索类名、方法名、接口实现等）
- 在聊天输入框支持 `@` 符号触发文件/类/方法引用（输入提示）
- 引用内容自动展开并注入到当前消息的上下文中

**分阶段实现**：

**8.1 PSI 结构化搜索（优先，0.5 天）**：
```kotlin
// 新建: src/main/kotlin/.../util/CodebaseSearcher.kt

object CodebaseSearcher {

    data class SearchResult(
        val filePath: String,
        val elementType: String,   // CLASS / METHOD / FIELD / INTERFACE
        val elementName: String,
        val lineNumber: Int,
        val snippet: String        // 前后各 3 行代码
    )

    /**
     * 通过 PSI 搜索：支持类名、方法名的模糊匹配
     * 比 grep 更精确，能理解代码结构
     */
    fun searchByName(project: Project, query: String): List<SearchResult>

    /**
     * 搜索接口的所有实现类
     */
    fun findImplementations(project: Project, interfaceName: String): List<SearchResult>

    /**
     * 搜索方法的所有调用点
     */
    fun findUsages(project: Project, className: String, methodName: String): List<SearchResult>
}
```

**8.2 @ 引用触发器（1 天）**：
- Web UI 输入框：输入 `@` 时弹出文件/类搜索下拉列表
- 选择后在消息中插入引用标记（`@文件名` 或 `@ClassName`）
- `ForgeChatPanel` 解析消息中的引用，自动读取并注入对应文件内容

**文件清单**：
```
新增: src/main/kotlin/.../util/CodebaseSearcher.kt
修改: src/main/kotlin/.../agent/AgentToolExecutor.kt（search_code 改用 PSI）
修改: src/main/resources/webui/chat.js（@ 引用触发 UI）
修改: src/main/kotlin/.../toolwindow/ForgeChatPanel.kt（@ 引用解析）
```

---

### Step 9：Checkpoint / 修改回滚（1 天）🟢 P2

**功能描述**：
每次 Agent 修改文件前，自动创建 Checkpoint（类似 git stash snapshot）。用户可以随时回滚到任意 Checkpoint 的状态，无需担心 AI 改坏代码。

**核心思路**：
- Checkpoint = 内存中保存的 `Map<filePath, originalContent>`
- 每次 Agent 开始任务（`runAgent()` 调用前），读取所有即将被修改的文件内容并保存
- 提供 `CheckpointManager.restore(checkpointId)` 一键恢复
- 在聊天面板底部显示 Checkpoint 时间线（最近 10 次）

**数据结构**：
```kotlin
// 新建: src/main/kotlin/.../agent/CheckpointManager.kt

object CheckpointManager {

    data class Checkpoint(
        val id: String,                         // UUID
        val timestamp: Long,
        val taskDescription: String,            // Agent 任务描述
        val snapshots: Map<String, String>      // filePath → content
    )

    private val checkpoints: ArrayDeque<Checkpoint> = ArrayDeque()
    private const val MAX_CHECKPOINTS = 10

    /** Agent 任务开始前调用 */
    fun createCheckpoint(project: Project, taskDescription: String): Checkpoint

    /** 回滚到指定 Checkpoint */
    fun restore(project: Project, checkpointId: String): Boolean

    /** 获取 Checkpoint 列表（最新在前）*/
    fun getAll(): List<Checkpoint>

    /** 清空所有 Checkpoint */
    fun clear()
}
```

**文件清单**：
```
新增: src/main/kotlin/.../agent/CheckpointManager.kt
修改: src/main/kotlin/.../agent/AgentService.kt（任务开始前创建 Checkpoint）
修改: src/main/resources/webui/chat.js（显示 Checkpoint 时间线）
```

---

### Step 10：多模态输入（图片/截图）🟢 P2

**功能描述**：
支持用户粘贴截图（Ctrl+V）或拖拽图片到聊天输入框，AI 根据图片内容回答问题（如根据 UI 截图生成代码、分析错误截图等）。

**前置条件**：
- 当前激活的 Provider 支持 Vision API（GPT-4o、Claude 3、Qwen-VL、Gemini 等）
- 图片需转为 base64 编码发送

**实现要点**：
- Web UI：输入框支持 paste 事件监听，检测到图片时显示预览缩略图
- JS Bridge：新增 `handleImageInput(base64, mimeType)` 消息类型
- `ForgeChatPanel`：将图片转为 `{type: "image_url", image_url: {url: "data:image/...;base64,..."}` 格式注入消息
- `AbstractLlmProvider`：已支持 multipart message，无需改动
- 不支持 Vision 的 Provider 激活时，隐藏图片输入区域并提示

**文件清单**：
```
修改: src/main/resources/webui/chat.html（图片粘贴 UI）
修改: src/main/resources/webui/chat.js（图片 base64 处理）
修改: src/main/kotlin/.../toolwindow/ForgeChatPanel.kt（图片消息处理）
```

---

## 三、时间线总览

```
Week 1 (4/16 - 4/18)  ──────────────────────────────────────────
  Day 1 (4/16): Step 1 — run_terminal 真实执行（0.5天）
                Step 3 — 当前文件上下文自动注入（0.5天）
  Day 2 (4/17): Step 2 — 多文件修改队列（数据结构 + Kotlin 层）
  Day 3 (4/18): Step 2 续 — 多文件 Diff UI 面板（Swing）

Week 2 (4/21 - 4/25)  ──────────────────────────────────────────
  Day 4 (4/21): Step 4 — Agent 步骤可视化（Web UI 工具卡片）
  Day 5 (4/22): Step 4 续 + Step 5 — 项目规则文件
  Day 6 (4/23): Step 6 — Git 深度集成（Commit Message / PR 描述）
  Day 7 (4/24): Step 7 — 多会话管理 UI 完善
  Day 8 (4/25): Step 8 — @Codebase PSI 结构化搜索

Week 3 (4/28 - 4/29)  ──────────────────────────────────────────
  Day 9  (4/28): Step 8 续 — @ 引用触发器
  Day 10 (4/29): Step 9 — Checkpoint 回滚 + 收尾测试
  
  Step 10（多模态输入）按需安排，不计入核心工期
```

---

## 四、依赖变更

### 4.1 无新增外部依赖

所有 Phase 2 功能均可在现有依赖基础上实现：
- **PSI API**：IntelliJ Platform SDK 内置
- **ProcessBuilder**：Java 标准库
- **多文件 Diff UI**：基于已有 `InlineDiffManager` 扩展 + IntelliJ Diff API
- **FileWatcher**：IntelliJ Platform SDK 内置（`VirtualFileManager.addVirtualFileListener`）

### 4.2 plugin.xml 变更

```xml
<!-- Step 6: Git Actions -->
<action id="CodeForge.GenerateCommitMessage"
        class="com.codeforge.plugin.idea.actions.GitActions$GenerateCommitMessageAction"
        text="Generate Commit Message (AI)"
        description="用 AI 分析 staged changes 生成 commit 信息">
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="first"/>
</action>

<action id="CodeForge.GeneratePRDescription"
        class="com.codeforge.plugin.idea.actions.GitActions$GeneratePRDescriptionAction"
        text="Generate PR Description (AI)"
        description="用 AI 生成 PR/MR 描述">
</action>
```

---

## 五、验收标准

### P0 必须达成

- [ ] `run_terminal` 工具能真实执行命令并返回输出给 LLM
  - [ ] `gradle build` → LLM 收到编译结果，能据此修复错误
  - [ ] 危险命令被拒绝，不弹确认框
  - [ ] 超时（60s）后自动终止
- [ ] Agent 修改多文件时，统一展示变更列表，不逐文件打断
  - [ ] 用户可全部接受/拒绝
  - [ ] 用户可单文件接受/拒绝
- [ ] 用户在编辑文件时发起对话，AI 自动知晓当前文件内容
  - [ ] "解释这个函数" → AI 知道当前打开的是哪个函数

### P1 应该达成

- [ ] Agent 工具调用过程显示为可展开的卡片（不再是黑箱）
- [ ] 项目根目录 `.codeforge.md` 被自动读取并注入到所有对话
- [ ] 右键菜单 → "Generate Commit Message" 生成符合规范的提交信息
- [ ] 聊天面板左侧显示会话历史列表，支持搜索

### P2 加分项

- [ ] Agent 任务前自动创建 Checkpoint，可随时一键回滚
- [ ] 聊天输入框支持 Ctrl+V 粘贴截图（Vision 模型可用时）
- [ ] `@` 触发文件/类搜索下拉，引用内容自动注入上下文

---

## 六、风险与注意事项

| 风险点 | 说明 | 应对方案 |
|--------|------|----------|
| `run_terminal` 安全性 | 命令执行有安全风险 | 黑名单 + 用户确认弹窗（可在设置中关闭确认） |
| 多文件 Diff UI 复杂度 | Swing 面板开发量较大 | 先实现简化版（列表 + 单文件 Accept/Reject），再迭代 |
| PSI API 跨语言兼容 | PSI 解析依赖语言插件 | 优先支持 Java/Kotlin，其他语言降级为 grep |
| 多模态大文件上传 | 图片 base64 可能超过 token 限制 | 限制图片分辨率（最大 1024x1024），超出自动压缩 |
| `.codeforge.md` 安全 | 恶意规则注入 | 文件内容长度限制（≤ 4000 字符），过滤特殊指令 |
