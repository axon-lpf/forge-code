
# Forge Code · 技术设计方案

> **Forge Code** —— 锻造每一行代码  
> 一款聚合国内外所有主流大模型、支持动态切换的 AI 编程助手插件，同时支持 IntelliJ IDEA 和 VSCode。

---

## 目录

1. [产品定位](#1-产品定位)
2. [整体架构](#2-整体架构)
3. [工程结构](#3-工程结构)
4. [后端服务设计](#4-后端服务设计)
5. [IDEA 插件设计](#5-idea-插件设计)
6. [VSCode 插件设计](#6-vscode-插件设计)
7. [插件内嵌 UI 设计](#7-插件内嵌-ui-设计)
8. [模型管理设计](#8-模型管理设计)
9. [核心功能设计](#9-核心功能设计)
10. [API 接口设计](#10-api-接口设计)
11. [数据存储设计](#11-数据存储设计)
12. [开发路线图](#12-开发路线图)

---

## 1. 产品定位

### 1.1 是什么

Forge Code 是一款 **IDE AI 编程助手插件**，核心特点：

- 🔗 **聚合模型**：一次集成，接入国内外 16+ 大模型平台（DeepSeek、通义千问、GLM、Kimi、GPT、Claude、Gemini 等）
- 🔄 **动态切换**：在 IDE 内底部状态栏一键切换模型，无需重启
- 🧠 **多模式对话**：支持 Vibe（直觉编程）和 Spec（规划驱动）两种模式
- 🛠️ **工具集成**：代码读取、文件搜索、代码 Apply、终端执行等 Agent 能力
- 🌐 **双 IDE 支持**：同一套后端，同时支持 IntelliJ IDEA 和 VSCode

### 1.2 和现有产品的差异

| 产品 | 模型 | 国内模型 | 自部署 | 开源 |
|------|------|----------|--------|------|
| GitHub Copilot | GPT/Claude | ❌ | ❌ | ❌ |
| CodeMaker | 多模型 | ✅ | ❌ | ❌ |
| 通义灵码 | Qwen | ✅ | ❌ | ❌ |
| **Forge Code** | **16+ 全部** | **✅** | **✅** | **✅** |

### 1.3 依赖关系

Forge Code 插件依赖 **claude-api-proxy** 后端服务作为大模型代理中台：

```
forge-code 插件  →  claude-api-proxy 后端  →  各大模型 API
```

两个工程**完全解耦**，插件只需知道后端地址（默认 `http://localhost:8080`）。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                          用户 IDE 环境                                │
│                                                                       │
│  ┌──────────────────────────┐    ┌──────────────────────────┐        │
│  │   IntelliJ IDEA           │    │        VSCode             │        │
│  │   forge-code-idea         │    │   forge-code-vscode       │        │
│  │                           │    │                           │        │
│  │  ┌────────────────────┐  │    │  ┌────────────────────┐  │        │
│  │  │  ToolWindow         │  │    │  │  WebView Panel      │  │        │
│  │  │  (JCEF 内嵌浏览器)  │  │    │  │  (VSCode WebView)   │  │        │
│  │  │  Chat UI            │  │    │  │  Chat UI            │  │        │
│  │  └────────────────────┘  │    │  └────────────────────┘  │        │
│  │                           │    │                           │        │
│  │  ┌────────────────────┐  │    │  ┌────────────────────┐  │        │
│  │  │  StatusBar Widget   │  │    │  │  StatusBar Item     │  │        │
│  │  │  模型切换            │  │    │  │  模型切换           │  │        │
│  │  └────────────────────┘  │    │  └────────────────────┘  │        │
│  │                           │    │                           │        │
│  │  ┌────────────────────┐  │    │  ┌────────────────────┐  │        │
│  │  │  Editor Actions     │  │    │  │  Commands/Menus     │  │        │
│  │  │  右键菜单/快捷键     │  │    │  │  右键菜单/命令面板   │  │        │
│  │  └────────────────────┘  │    │  └────────────────────┘  │        │
│  └──────────────┬───────────┘    └───────────────┬───────────┘        │
└─────────────────┼───────────────────────────────-┼────────────────────┘
                  │  HTTP REST + SSE 流式            │
                  └──────────────┬──────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│              claude-api-proxy  后端服务 (Spring Boot)                 │
│                         默认运行在 localhost:8080                     │
│                                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │ /plugin/chat  │  │/plugin/models│  │/plugin/config│               │
│  │ 流式对话      │  │ 模型列表/切换 │  │ 配置读写      │               │
│  └──────────────┘  └──────────────┘  └──────────────┘               │
│                                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │/plugin/session│ │/plugin/context│  │ (已有接口复用) │               │
│  │ 会话管理      │  │ 代码上下文    │  │ /v1/chat 等  │               │
│  └──────────────┘  └──────────────┘  └──────────────┘               │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  大模型提供商层 (已实现, 16+ 模型)                             │    │
│  │  DeepSeek · Qwen · GLM · Kimi · MiniMax · Ernie · Doubao    │    │
│  │  OpenAI · Claude · Gemini · Mistral · Groq · xAI ...        │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
                                 │
                    各大模型 API (动态路由)
```

---

## 3. 工程结构

### 3.1 工程关系

```
claude-api-proxy/        (现有工程 - 大模型代理后端，基本不动)
forge-code/              (新建工程 - IDE 插件主工程)
```

### 3.2 forge-code 工程目录

```
forge-code/
├── README.md
├── forge-code-design.md              # 本设计文档
│
├── plugin-idea/                      # IntelliJ IDEA 插件
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── settings.gradle.kts
│   └── src/
│       └── main/
│           ├── kotlin/
│           │   └── com/forgecode/plugin/idea/
│           │       ├── ForgeCodePlugin.kt          # 插件启动入口
│           │       ├── toolwindow/
│           │       │   ├── ForgeToolWindowFactory.kt   # 侧边栏注册
│           │       │   └── ForgeChatPanel.kt           # JCEF 聊天面板
│           │       ├── statusbar/
│           │       │   ├── ModelStatusBarFactory.kt    # 状态栏工厂
│           │       │   └── ModelStatusBarWidget.kt     # 模型切换 Widget
│           │       ├── actions/
│           │       │   ├── ExplainCodeAction.kt        # 解释代码
│           │       │   ├── OptimizeCodeAction.kt       # 优化代码
│           │       │   ├── ReviewCodeAction.kt         # 审查代码
│           │       │   └── GenerateTestAction.kt       # 生成测试
│           │       ├── settings/
│           │       │   ├── ForgeSettingsConfigurable.kt # 设置页入口
│           │       │   ├── ForgeSettingsComponent.kt    # 设置页 UI
│           │       │   └── ForgeSettings.kt             # 设置数据持久化
│           │       ├── service/
│           │       │   ├── BackendService.kt            # HTTP 调用后端
│           │       │   └── StreamHandler.kt             # SSE 流式处理
│           │       └── util/
│           │           ├── EditorUtil.kt                # 编辑器工具类
│           │           └── JcefUtil.kt                  # JCEF 工具类
│           └── resources/
│               ├── META-INF/
│               │   └── plugin.xml                       # 插件描述符
│               └── webui/                               # 内嵌 Web UI
│                   ├── index.html                       # 聊天页面主入口
│                   ├── js/
│                   │   ├── chat.js                      # 聊天逻辑
│                   │   ├── bridge.js                    # JS <-> Kotlin 通信桥
│                   │   └── marked.min.js                # Markdown 渲染
│                   └── css/
│                       └── style.css                    # 样式
│
├── plugin-vscode/                    # VSCode 插件 (后续实现)
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
│       ├── extension.ts              # 插件入口
│       ├── panel/
│       │   └── ChatPanel.ts          # WebView 聊天面板
│       ├── statusbar/
│       │   └── ModelStatusBar.ts     # 状态栏模型切换
│       ├── commands/
│       │   └── codeActions.ts        # 代码操作命令
│       └── service/
│           └── BackendService.ts     # HTTP 调用后端
│
└── plugin-shared/                    # 共享资源 (可选)
    └── webui/                        # 两端复用的 Chat UI 源码
        ├── index.html
        ├── chat.js
        └── style.css
```

---

## 4. 后端服务设计

### 4.1 现有工程改动（最小化）

`claude-api-proxy` 工程**几乎不需要改动**，只需新增一个 `PluginController`，提供对插件更友好的接口。

现有可复用接口：
- `POST /v1/chat` —— 直接复用，已支持 SSE 流式
- `GET/POST /api/config/*` —— 已支持模型配置读写

### 4.2 新增 Plugin 专用接口

新增文件：`src/main/java/com/claude/proxy/controller/PluginController.java`

```
POST   /api/plugin/chat               流式对话（SSE）
GET    /api/plugin/models             获取所有可用模型列表
POST   /api/plugin/models/active      切换当前激活模型
GET    /api/plugin/models/active      获取当前激活模型信息
GET    /api/plugin/health             健康检查（插件启动时探测后端）
GET    /api/plugin/sessions           获取会话列表
POST   /api/plugin/sessions           创建新会话
DELETE /api/plugin/sessions/{id}      删除会话
GET    /api/plugin/sessions/{id}      获取会话历史消息
```

### 4.3 模型列表接口响应格式

```json
GET /api/plugin/models

{
  "activeProvider": "deepseek",
  "activeModel": "deepseek-chat",
  "providers": [
    {
      "name": "deepseek",
      "displayName": "DeepSeek",
      "region": "cn",
      "enabled": true,
      "currentModel": "deepseek-chat",
      "models": ["deepseek-chat", "deepseek-reasoner"],
      "hasApiKey": true
    },
    {
      "name": "openai",
      "displayName": "OpenAI",
      "region": "global",
      "enabled": true,
      "currentModel": "gpt-4o",
      "models": ["gpt-4o", "gpt-4o-mini", "o3"],
      "hasApiKey": true
    }
    ...
  ]
}
```

### 4.4 流式对话接口

```
POST /api/plugin/chat
Content-Type: application/json

{
  "sessionId": "uuid-xxx",           // 会话ID（可选，用于多轮对话）
  "messages": [
    {"role": "system", "content": "你是一个专业的编程助手"},
    {"role": "user",   "content": "帮我解释这段代码：\n```java\n...\n```"}
  ],
  "context": {                        // 代码上下文（可选）
    "fileName": "HelloWorld.java",
    "language": "java",
    "selectedCode": "...",
    "fullFileContent": "..."
  },
  "stream": true
}
```

响应为 SSE 流，格式复用现有 OpenAI 兼容格式：
```
data: {"choices":[{"delta":{"content":"你好"},"index":0}]}
data: {"choices":[{"delta":{"content":"，这段代码"},"index":0}]}
data: [DONE]
```

---

## 5. IDEA 插件设计

### 5.1 技术选型

| 技术点 | 选型 | 原因 |
|--------|------|------|
| 开发语言 | Kotlin | JetBrains 官方推荐，语法简洁 |
| 构建工具 | Gradle + IntelliJ Platform Plugin | 官方标准 |
| UI 框架 | JCEF（内嵌 Chromium）| 渲染 HTML/CSS/JS，UI 最灵活 |
| HTTP 客户端 | OkHttp | 轻量，支持 SSE 流式 |
| JSON 解析 | Gson / Jackson | 与后端保持一致 |
| 最低支持版本 | IntelliJ IDEA 2023.1+ | JCEF 稳定版 |

### 5.2 plugin.xml 插件描述符

```xml
<idea-plugin>
  <id>com.forgecode.idea</id>
  <name>Forge Code</name>
  <version>1.0.0</version>
  <vendor>ForgeCode</vendor>
  <description>AI 编程助手，聚合国内外所有主流大模型</description>

  <depends>com.intellij.modules.platform</depends>
  <depends>com.intellij.modules.lang</depends>

  <extensions defaultExtensionNs="com.intellij">
    <!-- 侧边栏聊天窗口 -->
    <toolWindow
      id="Forge Code"
      anchor="right"
      factoryClass="com.forgecode.plugin.idea.toolwindow.ForgeToolWindowFactory"
      icon="/icons/forge_13.svg"/>

    <!-- 底部状态栏模型切换 -->
    <statusBarWidgetFactory
      id="ForgeModelStatusBar"
      implementation="com.forgecode.plugin.idea.statusbar.ModelStatusBarFactory"
      order="first"/>

    <!-- 设置页 -->
    <applicationConfigurable
      parentId="tools"
      instance="com.forgecode.plugin.idea.settings.ForgeSettingsConfigurable"
      displayName="Forge Code"/>

    <!-- 持久化设置存储 -->
    <applicationService
      serviceImplementation="com.forgecode.plugin.idea.settings.ForgeSettings"/>

    <!-- 后端服务（应用级单例）-->
    <applicationService
      serviceImplementation="com.forgecode.plugin.idea.service.BackendService"/>
  </extensions>

  <actions>
    <!-- 编辑器右键菜单 -->
    <group id="ForgeCode.EditorMenu" text="Forge Code" popup="true">
      <add-to-group group-id="EditorPopupMenu" anchor="last"/>
      <action id="ForgeCode.ExplainCode"
              class="com.forgecode.plugin.idea.actions.ExplainCodeAction"
              text="解释代码"/>
      <action id="ForgeCode.OptimizeCode"
              class="com.forgecode.plugin.idea.actions.OptimizeCodeAction"
              text="优化代码"/>
      <action id="ForgeCode.ReviewCode"
              class="com.forgecode.plugin.idea.actions.ReviewCodeAction"
              text="审查代码"/>
      <action id="ForgeCode.GenerateTest"
              class="com.forgecode.plugin.idea.actions.GenerateTestAction"
              text="生成单元测试"/>
    </group>
  </actions>
</idea-plugin>
```

### 5.3 ToolWindow 聊天面板

核心思路：使用 **JCEF**（JBCefBrowser）加载内嵌的 `webui/index.html`，通过 JS Bridge 和 Kotlin 代码双向通信。

```
ForgeChatPanel (Kotlin)
    │
    ├── JBCefBrowser          ← 内嵌 Chromium 浏览器
    │     └── 加载 webui/index.html
    │
    └── CefMessageRouter      ← JS <-> Kotlin 通信桥
          ├── JS 调用 Kotlin：window.cefQuery({request: ...})
          └── Kotlin 调用 JS：browser.executeJavaScript("...")
```

**JS → Kotlin 消息类型：**

```json
// 发送消息
{ "type": "sendMessage", "content": "帮我优化这段代码", "sessionId": "xxx" }

// 切换模型
{ "type": "switchModel", "provider": "deepseek", "model": "deepseek-chat" }

// 获取模型列表
{ "type": "getModels" }

// 新建会话
{ "type": "newSession" }

// 插入代码到编辑器
{ "type": "applyCode", "code": "...", "language": "java" }
```

**Kotlin → JS 消息类型：**

```javascript
// 追加流式文字
window.appendToken("你好，")

// 流式结束
window.onStreamDone()

// 更新模型列表
window.updateModels({activeProvider: "deepseek", providers: [...]})

// 错误提示
window.onError("连接后端失败，请检查服务是否启动")
```

### 5.4 状态栏模型切换 Widget

```
底部状态栏显示：  ⚡ DeepSeek · deepseek-chat   [点击]
                                ↓
                弹出 JBPopup 列表（按 cn/global 分组）
                ┌─────────────────────────────┐
                │ 🇨🇳 国内模型                  │
                │   ✓ DeepSeek · deepseek-chat │  ← 当前激活
                │     通义千问 · qwen-max       │
                │     智谱GLM · glm-4-plus      │
                │     Kimi · moonshot-v1-128k   │
                │ 🌍 国外模型                   │
                │     OpenAI · gpt-4o           │
                │     Claude · claude-sonnet-4  │
                │     Gemini · gemini-2.5-pro   │
                └─────────────────────────────┘
```

点击某个模型后：
1. 调用 `POST /api/plugin/models/active` 切换后端激活模型
2. 刷新状态栏显示
3. 通知 JCEF 面板更新当前模型显示

### 5.5 设置页

插件设置页（`File → Settings → Tools → Forge Code`）：

```
Forge Code Settings
─────────────────────────────────────────────
后端服务地址:   [ http://localhost:8080     ]
连接超时(秒):   [ 30                        ]
读取超时(秒):   [ 120                       ]

[测试连接]  → 成功: ✅ 已连接，当前模型: deepseek-chat
             失败: ❌ 无法连接，请检查后端是否启动

─────────────────────────────────────────────
主题:  ● 跟随 IDE   ○ 深色   ○ 浅色
语言:  ● 中文       ○ English
─────────────────────────────────────────────
```

### 5.6 BackendService 核心实现思路

```kotlin
// BackendService.kt — 应用级单例服务
class BackendService : PersistentStateComponent<BackendService.State> {

    // 普通 POST 请求
    fun getModels(): ModelsResponse

    // SSE 流式请求（回调方式）
    fun chat(
        request: ChatRequest,
        onToken: (String) -> Unit,      // 收到一个 token
        onDone: () -> Unit,             // 流结束
        onError: (Exception) -> Unit    // 出错
    )

    // 切换模型
    fun switchModel(provider: String, model: String)

    // 健康检查
    fun healthCheck(): Boolean
}
```

SSE 流式实现使用 OkHttp EventSource：

```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

val request = Request.Builder()
    .url("$baseUrl/api/plugin/chat")
    .post(jsonBody)
    .build()

val eventSource = EventSources.createFactory(client)
    .newEventSource(request, object : EventSourceListener() {
        override fun onEvent(source, id, type, data) {
            if (data == "[DONE]") {
                onDone()
                return
            }
            // 解析 delta content 并回调
            val token = parseToken(data)
            if (token != null) onToken(token)
        }
        override fun onFailure(source, t, response) {
            onError(Exception(t))
        }
    })
```

---

## 6. VSCode 插件设计

> **Phase 2 实现，此处为预设计**

### 6.1 技术选型

| 技术点 | 选型 |
|--------|------|
| 开发语言 | TypeScript |
| 构建工具 | webpack + esbuild |
| UI 面板 | VSCode WebView API |
| HTTP 客户端 | node-fetch / axios |
| SSE 处理 | eventsource-parser |

### 6.2 核心模块

```typescript
// extension.ts 入口
export function activate(context: ExtensionContext) {
    // 注册侧边栏聊天面板
    const chatProvider = new ChatViewProvider(context);
    context.subscriptions.push(
        window.registerWebviewViewProvider('forgecode.chat', chatProvider)
    );

    // 注册底部状态栏
    const statusBar = new ModelStatusBar(context);

    // 注册命令
    context.subscriptions.push(
        commands.registerCommand('forgecode.explainCode', explainCode),
        commands.registerCommand('forgecode.optimizeCode', optimizeCode),
        commands.registerCommand('forgecode.reviewCode', reviewCode),
    );
}
```

### 6.3 WebView 通信

VSCode WebView 和插件主进程通过 `postMessage` 通信：

```typescript
// 主进程 → WebView
panel.webview.postMessage({ type: 'appendToken', content: '你好' });

// WebView → 主进程
window.addEventListener('message', event => {
    const msg = event.data;
    vscode.postMessage({ type: 'sendMessage', content: msg.content });
});
```

---

## 7. 插件内嵌 UI 设计

### 7.1 UI 复用策略

聊天 UI 使用**纯 HTML/CSS/JS**实现，不依赖任何框架（避免构建复杂度），可在以下两个环境中直接运行：

- IDEA 插件中：JCEF 内嵌 Chromium 加载
- VSCode 插件中：WebView 加载

差异部分通过检测运行环境自动适配：

```javascript
// bridge.js — 自动识别运行环境
const bridge = {
    isIdea: typeof window.cefQuery !== 'undefined',
    isVscode: typeof acquireVsCodeApi !== 'undefined',

    send(message) {
        if (this.isIdea) {
            // IDEA JCEF 环境
            window.cefQuery({ request: JSON.stringify(message) });
        } else if (this.isVscode) {
            // VSCode WebView 环境
            vscode.postMessage(message);
        }
    }
};
```

### 7.2 页面结构

```
┌──────────────────────────────────────────┐
│  🔥 Forge Code            [⚙] [+] [🕐]   │  ← 顶部工具栏
├──────────────────────────────────────────┤
│  Chat  │  Review  │  Coverage            │  ← Tab 切换
├──────────────────────────────────────────┤
│                                          │
│         🔥 Forge Code                    │
│         锻造每一行代码                    │
│                                          │
│  ┌──────────────────┐ ┌───────────────┐  │
│  │  Vibe            │ │  Spec         │  │
│  │  边聊边写，先跑   │ │  先规划后实现  │  │
│  │  起来再优化       │ │               │  │
│  └──────────────────┘ └───────────────┘  │
│                                          │
│  适用场景：                               │
│  · 通过对话描述想法，无需文档             │
│  · 基于反馈进行增量式改进                 │
│  · 适合：原型开发、探索性编程             │
│                                          │
│  推荐问题 ∧                              │
│                                          │
├──────────────────────────────────────────┤
│  📁 代码仓库 my-project                  │  ← 当前工程
├──────────────────────────────────────────┤
│  [+ 附件] [输入消息...              ] [▶] │  ← 输入框
├──────────────────────────────────────────┤
│  Agent仓库智聊 ∨ │ ⚡ DeepSeek·deepseek-chat ∨ │  ← 底部模式/模型
└──────────────────────────────────────────┘
```

### 7.3 对话消息渲染

```
用户消息：
┌─────────────────────────────────────────┐
│ 帮我优化这段冒泡排序                     │ 右对齐，用户气泡
└─────────────────────────────────────────┘

AI 消息：
🔥  当然，我来帮你优化这段代码。
    
    原始代码的时间复杂度是 O(n²)，
    可以通过以下方式优化：
    
    ```java
    // 优化后的版本
    public void sort(int[] arr) {
        ...
    }
    ```
    
    [复制] [插入到编辑器] [在新文件中打开]
```

---

## 8. 模型管理设计

### 8.1 模型选择弹窗

点击底部状态栏模型名称后弹出：

```
┌─────────────────────────────────────────┐
│  商业模型（调用商业api）                  │
│  ✦ GLM 5.1              [New]           │
│  ✦ GLM 5.1 Thinking     [New]           │
│  ✦ Qwen3.6 Plus         [New]           │
│  ✦ GLM 5 Turbo                          │
│  ✦ MiniMax M2.7                         │
│  ⊙ GPT 5.4                              │
│  ⊙ Claude 4.6 Sonnet                    │
│  ⊙ Gemini 2.5 Pro                       │
│  ─────────────────────────────────────  │
│  ● DeepSeek Chat          ← 当前选中     │
│  ─────────────────────────────────────  │
│  未配置 API Key 的模型显示为灰色          │
│  [模型说明 ?]                    [⚙ 设置]│
└─────────────────────────────────────────┘
```

### 8.2 模型分组逻辑

```
所有模型
├── 🇨🇳 国内模型
│   ├── 已配置 API Key（可用，正常显示）
│   │   ├── DeepSeek · deepseek-chat
│   │   └── 通义千问 · qwen-max
│   └── 未配置（灰色，点击跳转配置）
│       ├── 智谱GLM（未配置）
│       └── Kimi（未配置）
└── 🌍 国外模型
    ├── 已配置
    └── 未配置
```

---

## 9. 核心功能设计

### 9.1 Vibe 模式（对话式编程）

- 用户用自然语言描述需求
- AI 直接生成/修改代码
- 支持 `@文件名` 引用项目文件
- 支持粘贴代码片段到对话框
- AI 回复的代码块支持一键 **Apply**（写入文件）

### 9.2 Spec 模式（规划驱动）

- 先让 AI 生成实现计划（Plan）
- 用户确认或修改计划
- 再按计划逐步执行
- 每步执行前需用户确认（类似 Plan Mode）

### 9.3 代码 Apply 功能

当 AI 返回代码修改建议时，用户点击 **Apply** 按钮：

```
IDEA 插件实现：
1. 解析 AI 返回的代码块（语言、内容）
2. 通过 JS Bridge 发送 applyCode 消息到 Kotlin
3. Kotlin 调用 IDE API 打开 Diff 视图
4. 用户在 Diff 视图中确认/拒绝修改
5. 确认后写入文件
```

### 9.4 代码上下文注入

用户在编辑器中选中代码，触发右键菜单操作时，自动将以下信息作为上下文：

```
- 当前文件名和语言类型
- 选中的代码片段
- 文件的完整内容（可选，受 token 限制）
- 光标位置
- 项目名称
```

### 9.5 多会话管理

```
会话列表（类似浏览器标签）：
[+ 新建会话]

会话 1: 优化排序算法 (今天 14:30)
会话 2: 数据库连接问题 (今天 10:15)
会话 3: API 设计讨论 (昨天)
```

---

## 10. API 接口设计

### 10.1 接口汇总

| Method | Path | 说明 | 优先级 |
|--------|------|------|--------|
| GET | `/api/plugin/health` | 健康检查 | P0 |
| POST | `/api/plugin/chat` | 流式对话 | P0 |
| GET | `/api/plugin/models` | 获取模型列表 | P0 |
| POST | `/api/plugin/models/active` | 切换激活模型 | P0 |
| GET | `/api/plugin/models/active` | 获取当前激活模型 | P0 |
| POST | `/api/plugin/sessions` | 创建会话 | P1 |
| GET | `/api/plugin/sessions` | 会话列表 | P1 |
| GET | `/api/plugin/sessions/{id}` | 会话历史消息 | P1 |
| DELETE | `/api/plugin/sessions/{id}` | 删除会话 | P1 |
| POST | `/api/plugin/sessions/{id}/messages` | 向指定会话发消息 | P1 |

### 10.2 健康检查

```
GET /api/plugin/health

Response 200:
{
  "status": "ok",
  "version": "1.0.0",
  "activeProvider": "deepseek",
  "activeModel": "deepseek-chat"
}
```

### 10.3 切换激活模型

```
POST /api/plugin/models/active
{
  "provider": "openai",
  "model": "gpt-4o"
}

Response 200:
{
  "success": true,
  "activeProvider": "openai",
  "activeModel": "gpt-4o"
}
```

---

## 11. 数据存储设计

### 11.1 后端存储（复用现有）

后端 `claude-api-proxy` 已有 `data/config.json`，存储所有 API Key 和模型配置，插件通过 API 读写，无需额外处理。

### 11.2 插件本地存储

插件本身只存储少量 UI 配置，通过 IntelliJ Platform 的持久化机制存储：

```kotlin
// ForgeSettings.kt
@State(name = "ForgeCodeSettings", storages = [Storage("forge-code.xml")])
class ForgeSettings : PersistentStateComponent<ForgeSettings.State> {
    data class State(
        var backendUrl: String = "http://localhost:8080",
        var connectTimeout: Int = 30,
        var readTimeout: Int = 120,
        var theme: String = "auto",          // auto / dark / light
        var language: String = "zh",         // zh / en
        var defaultMode: String = "vibe"     // vibe / spec
    )
}
```

存储位置：`~/.config/JetBrains/[IDE版本]/options/forge-code.xml`

### 11.3 会话存储

会话数据统一存储在后端 `data/sessions/` 目录（已有实现），插件通过 REST API 读写，实现多设备/多 IDE 共享同一套会话历史。

---

## 12. 开发路线图

### Phase 1 · MVP（目标：2周内可用）

**目标**：IDEA 插件能对话、能切换模型

- [ ] 搭建 `forge-code` 工程骨架（Kotlin + Gradle）
- [ ] 实现 IDEA ToolWindow + JCEF 聊天面板
- [ ] 实现基础聊天 UI（HTML/CSS/JS）
- [ ] 实现 JS Bridge（消息发送/流式接收）
- [ ] 实现底部状态栏模型切换 Widget
- [ ] 实现 Settings 设置页（后端地址配置）
- [ ] 后端新增 `/api/plugin/health`、`/api/plugin/models` 接口

**交付物**：可在 IDEA 中安装的 `.zip` 插件包，能聊天、能切换模型

---

### Phase 2 · 功能完善（目标：1个月内）

**目标**：编辑器联动、代码操作、多会话

- [ ] 实现右键菜单（解释/优化/审查/生成测试）
- [ ] 实现代码上下文自动注入（选中代码 + 文件信息）
- [ ] 实现代码 Apply（Diff 视图确认修改）
- [ ] 实现多会话管理（新建/切换/删除）
- [ ] 实现 Spec 模式（Plan → 确认 → 执行）
- [ ] 后端完善会话存储 API

**交付物**：可发布到 JetBrains Marketplace 的完整版本

---

### Phase 3 · 高级功能（目标：2个月内）

**目标**：Agent 能力、VSCode 插件

- [ ] VSCode 插件实现（复用后端和 UI）
- [ ] Rules 规则系统（项目级持久上下文）
- [ ] 代码库索引（项目文件 Glob/Grep 搜索）
- [ ] MCP Server 支持（Model Context Protocol）
- [ ] Inline Completion（行内代码补全）
- [ ] Review Tab（代码审查专用视图）
- [ ] Coverage Tab（测试覆盖率分析）

---

## 附录：技术参考资料

| 资源 | 链接 |
|------|------|
| IntelliJ Platform SDK 文档 | https://plugins.jetbrains.com/docs/intellij/welcome.html |
| JCEF in IntelliJ 文档 | https://plugins.jetbrains.com/docs/intellij/jcef.html |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template |
| VSCode Extension API | https://code.visualstudio.com/api |
| OkHttp SSE | https://square.github.io/okhttp/recipes/#handle-server-sent-events-kt-java |
| claude-api-proxy 后端 | 本地工程 `claude-api-proxy/` |
