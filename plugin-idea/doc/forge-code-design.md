
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

### 1.3 架构演进

~~**v0.x（旧方案）** — 插件依赖外部后端服务 `claude-api-proxy`：~~

```
~~forge-code 插件  →  claude-api-proxy 后端  →  各大模型 API~~
```

**v1.0（当前方案）** — 将 `claude-api-proxy` 的大模型调用核心直接内置到插件中，**零依赖启动**：

```
forge-code 插件（内置 LLM Provider 层）  →  各大模型 API
```

核心变化：
- ✅ **无需后端** — 插件直接通过 OkHttp 调用各大模型 API，无需启动额外服务
- ✅ **零配置启动** — 安装插件后只需填写 API Key 即可使用
- ✅ **配置本地化** — 所有配置（API Key、模型选择等）通过 IntelliJ 持久化机制存储在本地
- ✅ **Java + Kotlin 混编** — Provider 层使用 Java（从 claude-api-proxy 移植），插件层使用 Kotlin

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                       IntelliJ IDEA 插件                              │
│                       forge-code-idea                                 │
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      插件 UI 层 (Kotlin)                       │   │
│  │                                                                │   │
│  │  ┌────────────────┐ ┌────────────────┐ ┌─────────────────┐  │   │
│  │  │  ToolWindow     │ │  StatusBar     │ │  Editor Actions  │  │   │
│  │  │  JCEF Chat UI   │ │  模型切换       │ │  右键菜单/快捷键  │  │   │
│  │  └───────┬─────────┘ └───────┬────────┘ └────────┬────────┘  │   │
│  │          │                   │                    │            │   │
│  │          └───────────────────┼────────────────────┘            │   │
│  │                              ▼                                 │   │
│  │                    ┌──────────────────┐                        │   │
│  │                    │  LlmService      │  ← Kotlin 服务层       │   │
│  │                    │  (应用级单例)     │                        │   │
│  │                    └────────┬─────────┘                        │   │
│  └─────────────────────────────┼─────────────────────────────────┘   │
│                                ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │            LLM Provider 层 (Java，移植自 claude-api-proxy)     │   │
│  │                                                                │   │
│  │  ┌───────────────────────────────────────────────────────┐   │   │
│  │  │  ProviderRegistry — 16+ 模型元信息注册表                │   │   │
│  │  │  DeepSeek · Qwen · GLM · Kimi · MiniMax · Ernie       │   │   │
│  │  │  Doubao · Yi · Baichuan · StepFun                      │   │   │
│  │  │  OpenAI · Claude · Gemini · Mistral · Groq · xAI      │   │   │
│  │  └───────────────────────────────────────────────────────┘   │   │
│  │                                                                │   │
│  │  ┌─────────────────┐  ┌───────────────────────────────┐     │   │
│  │  │  LlmProvider     │  │  AbstractLlmProvider           │     │   │
│  │  │  (接口)           │  │  OkHttp + SSE 流式调用          │     │   │
│  │  └─────────────────┘  └───────────────────────────────┘     │   │
│  │                                                                │   │
│  │  ┌──────────────────┐ ┌──────────────┐ ┌────────────────┐  │   │
│  │  │GenericOpenAI     │ │DeepSeek      │ │MiniMax         │  │   │
│  │  │Provider          │ │Provider      │ │Provider        │  │   │
│  │  │(通用 OpenAI 兼容) │ │(推理模型特殊) │ │(图片上传特殊)  │  │   │
│  │  └──────────────────┘ └──────────────┘ └────────────────┘  │   │
│  │                                                                │   │
│  │  ┌───────────────────────────────────────────────────────┐   │   │
│  │  │  ProviderManager — Provider 实例管理工厂                │   │   │
│  │  │  根据用户配置动态创建/切换 Provider 实例                 │   │   │
│  │  └───────────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                │                                      │
│                   OkHttp 直接调用各大模型 API                          │
└────────────────────────────────┼──────────────────────────────────────┘
                                 ▼
          ┌──────────────────────────────────────────┐
          │          各大模型 API (动态路由)            │
          │  api.deepseek.com / api.openai.com / ...  │
          └──────────────────────────────────────────┘
```

---

## 3. 工程结构

### 3.1 工程关系

```
claude-api-proxy/        (原有工程 - 仅作为代码参考源，核心 Provider 层移植到插件中)
forge-code/              (主工程 - IDE 插件，内置大模型调用能力)
```

### 3.2 Java + Kotlin 混编策略

本工程采用 **Java + Kotlin 混编**：

| 层级 | 语言 | 原因 |
|------|------|------|
| LLM Provider 层 | **Java** | 从 claude-api-proxy 移植，保持原样，减少移植成本 |
| 插件 UI/服务层 | **Kotlin** | JetBrains 官方推荐，语法简洁 |

Gradle 已配置 `id("java")` + `id("org.jetbrains.kotlin.jvm")`，Java 和 Kotlin **天然互通**：
- Java 代码放在 `src/main/java/` 目录
- Kotlin 代码放在 `src/main/kotlin/` 目录
- 两者可互相调用，编译器自动处理

### 3.3 forge-code 工程目录

```
forge-code/
├── README.md
│
├── plugin-idea/                      # IntelliJ IDEA 插件
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── settings.gradle.kts
│   ├── doc/
│   │   └── forge-code-design.md      # 本设计文档
│   └── src/
│       └── main/
│           ├── java/                                    # ★ Java 代码（LLM Provider 层）
│           │   └── com/forgecode/plugin/llm/
│           │       ├── dto/                             # 请求/响应 DTO
│           │       │   ├── ChatRequest.java              # OpenAI 兼容请求格式
│           │       │   └── ChatResponse.java             # OpenAI 兼容响应格式
│           │       ├── provider/                         # 模型提供商
│           │       │   ├── LlmProvider.java              # 提供商统一接口
│           │       │   ├── AbstractLlmProvider.java      # 基类（OkHttp + SSE）
│           │       │   ├── ProviderRegistry.java         # 16+ 模型元信息注册表
│           │       │   └── impl/                         # 具体实现
│           │       │       ├── GenericOpenAIProvider.java  # 通用 OpenAI 兼容
│           │       │       ├── DeepSeekProvider.java       # DeepSeek 特殊逻辑
│           │       │       └── MiniMaxProvider.java        # MiniMax 特殊逻辑
│           │       └── ProviderManager.java              # Provider 工厂/管理器
│           │
│           ├── kotlin/                                  # ★ Kotlin 代码（插件层）
│           │   └── com/forgecode/plugin/idea/
│           │       ├── toolwindow/
│           │       │   ├── ForgeToolWindowFactory.kt     # 侧边栏注册
│           │       │   ├── ForgeChatPanel.kt             # JCEF 聊天面板
│           │       │   └── ModelChangedListener.kt       # 模型切换事件
│           │       ├── statusbar/
│           │       │   └── ModelStatusBarWidget.kt       # 状态栏模型切换
│           │       ├── actions/
│           │       │   └── CodeActions.kt                # 代码操作（解释/优化/审查/测试）
│           │       ├── settings/
│           │       │   ├── ForgeSettings.kt              # 设置数据持久化（含 Provider 配置）
│           │       │   └── ForgeSettingsConfigurable.kt  # 设置页 UI
│           │       ├── service/
│           │       │   └── LlmService.kt                 # ★ LLM 服务（替代旧 BackendService）
│           │       └── util/
│           │           └── EditorUtil.kt                 # 编辑器工具类
│           │
│           └── resources/
│               ├── META-INF/
│               │   └── plugin.xml                       # 插件描述符
│               └── webui/                               # 内嵌 Web UI
│                   ├── index.html
│                   ├── js/
│                   │   ├── chat.js
│                   │   ├── bridge.js
│                   │   └── marked.min.js
│                   └── css/
│                       └── style.css
│
├── plugin-vscode/                    # VSCode 插件 (后续实现)
│   └── ...
│
└── plugin-shared/                    # 共享资源 (可选)
    └── webui/
```

---

## 4. LLM Provider 层设计（内置，移植自 claude-api-proxy）

> **核心变化**：不再依赖外部后端服务，将 `claude-api-proxy` 的 Provider 层直接移植到插件中。
> 移植代码使用 **Java** 编写，包名从 `com.claude.proxy` 改为 `com.forgecode.plugin.llm`。

### 4.1 移植范围

从 `claude-api-proxy` 移植以下模块（**去掉 Spring 依赖，去掉 Lombok**）：

| 原文件 (claude-api-proxy) | 新文件 (forge-code) | 改动说明 |
|---------------------------|---------------------|----------|
| `dto/openai/OpenAIChatRequest.java` | `dto/ChatRequest.java` | 重命名，手写 getter/setter |
| `dto/openai/OpenAIChatResponse.java` | `dto/ChatResponse.java` | 重命名，手写 getter/setter |
| `provider/LlmProvider.java` | `provider/LlmProvider.java` | 原样移植 |
| `provider/AbstractLlmProvider.java` | `provider/AbstractLlmProvider.java` | 去掉 Lombok `@Slf4j`，用 IntelliJ Logger |
| `provider/ProviderRegistry.java` | `provider/ProviderRegistry.java` | 原样移植（纯静态数据） |
| `provider/impl/GenericOpenAIProvider.java` | `provider/impl/GenericOpenAIProvider.java` | 去掉 Lombok |
| `provider/impl/DeepSeekProvider.java` | `provider/impl/DeepSeekProvider.java` | 去掉 Lombok |
| `provider/impl/MiniMaxProvider.java` | `provider/impl/MiniMaxProvider.java` | 去掉 Lombok |
| `provider/LlmProviderFactory.java` | `ProviderManager.java` | 去掉 Spring，改为手动初始化 |
| `config/ConfigStore.java` | _(废弃，用 ForgeSettings 替代)_ | 不移植，用 IntelliJ 持久化替代 |

### 4.2 不移植的模块

以下模块**不移植**到插件中（后端专用，插件不需要）：

- `controller/` — REST Controller（插件不需要 HTTP 接口）
- `converter/` — Anthropic 格式转换（代理功能专用）
- `config/ConfigStore.java` — JSON 文件存储（改用 IntelliJ PersistentState）
- `dto/anthropic/` — Anthropic 原生格式（仅代理用）
- Spring Boot 启动类、配置文件等

### 4.3 核心类设计

#### 4.3.1 LlmProvider 接口

```java
// com.forgecode.plugin.llm.provider.LlmProvider
public interface LlmProvider {
    String getName();           // "deepseek"
    String getDisplayName();    // "DeepSeek"
    String getDefaultModel();
    String getCurrentModel();
    void setCurrentModel(String model);
    List<String> getAvailableModels();

    // 非流式调用
    ChatResponse chatCompletion(ChatRequest request) throws IOException;

    // 流式调用 (SSE)
    void chatCompletionStream(ChatRequest request,
                               Consumer<String> onEvent,
                               Runnable onComplete,
                               Consumer<Throwable> onError);
}
```

#### 4.3.2 ProviderRegistry（静态元信息注册表）

从 claude-api-proxy **原样移植**，包含 16+ 模型提供商的元信息（名称、默认 URL、支持的模型列表等）。

这是纯静态数据，不包含用户配置，无需任何改动。

#### 4.3.3 ProviderManager（Provider 工厂管理器）

替代原来的 `LlmProviderFactory`，去掉 Spring 依赖：

```java
// com.forgecode.plugin.llm.ProviderManager
public class ProviderManager {

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();
    private volatile String activeProviderName;
    private volatile String activeModelName;

    /**
     * 根据用户配置初始化所有 Provider
     * 由 Kotlin 层的 LlmService 在启动时调用
     */
    public void init(Map<String, ProviderConfig> configs,
                     String activeProvider, String activeModel) { ... }

    /** 获取当前激活的 Provider */
    public LlmProvider getActiveProvider() { ... }

    /** 切换 Provider 和模型 */
    public void switchProvider(String providerName, String model) { ... }

    /** 获取所有已初始化的 Provider */
    public Map<String, LlmProvider> getAllProviders() { ... }

    /** 重新加载单个 Provider（配置变更时） */
    public void reloadProvider(String name, ProviderConfig config) { ... }

    /**
     * Provider 配置（简化版，由 Kotlin ForgeSettings 传入）
     */
    public static class ProviderConfig {
        public boolean enabled;
        public String apiKey;
        public String baseUrl;
        public String currentModel;
        public String displayName;
        public List<String> models;
        public String chatPath;
        public int connectTimeout = 30;
        public int readTimeout = 120;
    }
}
```

### 4.4 Kotlin 服务层 — LlmService

`LlmService` 是 **Kotlin 服务层**，替代旧的 `BackendService`，作为插件其他组件调用大模型的统一入口。

```kotlin
// com.forgecode.plugin.idea.service.LlmService
@Service(Service.Level.APP)
class LlmService {

    private val providerManager = ProviderManager()

    /** 初始化（读取 ForgeSettings 中的 Provider 配置） */
    fun init() {
        val settings = ForgeSettings.getInstance()
        providerManager.init(
            settings.providerConfigs,  // Map<String, ProviderConfig>
            settings.activeProvider,
            settings.activeModel
        )
    }

    /** 获取当前激活的模型信息 */
    fun getActiveInfo(): ActiveInfo { ... }

    /** 获取所有模型提供商列表（供 UI 展示） */
    fun getProviderList(): List<ProviderInfo> { ... }

    /** 切换模型 */
    fun switchModel(provider: String, model: String) { ... }

    /** 流式对话 */
    fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) { ... }
}
```

### 4.5 数据流

```
用户在聊天面板输入消息
    │
    ▼
ForgeChatPanel (Kotlin, JCEF)
    │  handleSendMessage()
    ▼
LlmService (Kotlin, 应用级单例)
    │  chatStream()
    ▼
ProviderManager (Java)
    │  getActiveProvider()
    ▼
LlmProvider 实现类 (Java)
    │  chatCompletionStream()  ← OkHttp SSE
    ▼
大模型 API (api.deepseek.com / api.openai.com / ...)
    │
    │  SSE events: data: {"choices":[{"delta":{"content":"你好"}}]}
    ▼
LlmProvider → onEvent callback
    │
    ▼
LlmService → onToken callback
    │
    ▼
ForgeChatPanel → executeJS("window.appendToken(`你好`)")
    │
    ▼
JCEF 浏览器渲染 Markdown
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
─────────────────────────────────────────────────────────────
 🤖 模型提供商配置
─────────────────────────────────────────────────────────────
 🇨🇳 国内模型

 提供商             API Key                    状态      操作
 ─────────         ──────────────            ──────    ──────
 DeepSeek          sk-****a1b2              ✅ 可用    [测试] [编辑]
 通义千问 (Qwen)    sk-****c3d4              ✅ 可用    [测试] [编辑]
 智谱 GLM           (未配置)                 ⚠️ 未配置  [配置]
 Kimi (Moonshot)    (未配置)                 ⚠️ 未配置  [配置]
 MiniMax            (未配置)                 ⚠️ 未配置  [配置]
 ...

 🌍 国外模型

 OpenAI             sk-****e5f6              ✅ 可用    [测试] [编辑]
 Anthropic (Claude)  (未配置)                ⚠️ 未配置  [配置]
 Google Gemini       (未配置)                ⚠️ 未配置  [配置]
 ...

 [+ 添加自定义提供商]

─────────────────────────────────────────────────────────────
 ⚙️ 通用设置
─────────────────────────────────────────────────────────────
 当前激活:    [ DeepSeek · deepseek-chat    ▼ ]
 连接超时(秒): [ 30  ]
 读取超时(秒): [ 120 ]

─────────────────────────────────────────────────────────────
 🎨 界面
─────────────────────────────────────────────────────────────
 主题:  ● 跟随 IDE   ○ 深色   ○ 浅色
 语言:  ● 中文       ○ English
─────────────────────────────────────────────────────────────
```

点击 [配置] 或 [编辑] 后弹出对话框：

```
╔═══════════════════════════════════════════╗
║  配置 DeepSeek                            ║
╠═══════════════════════════════════════════╣
║                                           ║
║  API Key:    [ sk-xxxxxxxxxxxxxxxx      ] ║
║  Base URL:   [ https://api.deepseek.com ] ║  ← 可选，留空用默认
║  模型:       [ deepseek-chat         ▼  ] ║
║                                           ║
║  获取 API Key → https://platform.deepseek.com ║
║                                           ║
║         [测试连通性]  [保存]  [取消]        ║
╚═══════════════════════════════════════════╝
```

### 5.6 LlmService 核心实现思路

`LlmService` 替代旧的 `BackendService`，不再通过 HTTP 调外部后端，而是直接调用内置的 `ProviderManager`：

```kotlin
// LlmService.kt — 应用级单例服务
@Service(Service.Level.APP)
class LlmService {

    private val providerManager = ProviderManager()
    private val gson = Gson()

    /** 插件启动时初始化 */
    fun init() {
        val settings = ForgeSettings.getInstance()
        providerManager.init(
            settings.getProviderConfigMap(),
            settings.activeProvider,
            settings.activeModel
        )
    }

    /** 获取当前激活信息 */
    fun getActiveInfo(): ActiveInfo {
        val provider = providerManager.getActiveProvider()
        return ActiveInfo(provider?.name, provider?.currentModel)
    }

    /** 获取所有 Provider 列表（供 UI 展示） */
    fun getProviderList(): List<ProviderInfo> { ... }

    /** 切换模型 */
    fun switchModel(provider: String, model: String): Boolean {
        providerManager.switchProvider(provider, model)
        // 持久化到 ForgeSettings
        val settings = ForgeSettings.getInstance()
        settings.activeProvider = provider
        settings.activeModel = model
        return true
    }

    /** 流式对话 — 直接调用 Provider，不走 HTTP */
    fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val provider = providerManager.getActiveProvider()
            ?: throw IllegalStateException("未配置任何可用的模型提供商")

        val request = buildChatRequest(messages)

        provider.chatCompletionStream(
            request,
            { data ->  // onEvent
                if (data == "[DONE]") { onDone(); return@chatCompletionStream }
                val token = parseTokenFromChunk(data)
                if (token != null) onToken(token)
            },
            { onDone() },       // onComplete
            { error ->          // onError
                onError(Exception(error))
            }
        )
    }
}
```

关键变化总结：
- ❌ ~~通过 HTTP 调用外部后端~~ → ✅ **直接调用 Java ProviderManager**
- ❌ ~~BackendService.healthCheck()~~ → ✅ **LlmService.getActiveInfo()（本地方法调用）**
- ❌ ~~BackendService.getModels() → HTTP GET~~ → ✅ **LlmService.getProviderList()（读内存）**
- ❌ ~~BackendService.chat() → SSE over HTTP~~ → ✅ **LlmService.chatStream()（直接调 Provider）**
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

## 10. 内部接口设计（LlmService API）

> **注意**：v1.0 架构不再使用 HTTP REST 接口，所有调用都是进程内方法调用。
> 以下描述的是 `LlmService` 的 Kotlin API，供 ForgeChatPanel、StatusBar 等组件调用。

### 10.1 接口汇总

| 方法 | 返回类型 | 说明 | 优先级 |
|------|----------|------|--------|
| `LlmService.getActiveInfo()` | `ActiveInfo` | 获取当前激活的 Provider 和 Model | P0 |
| `LlmService.chatStream(...)` | `void` (callback) | 流式对话，通过回调返回 token | P0 |
| `LlmService.getProviderList()` | `List<ProviderInfo>` | 获取所有 Provider 列表（含状态） | P0 |
| `LlmService.switchModel(...)` | `Boolean` | 切换激活模型并持久化 | P0 |
| `LlmService.testProvider(...)` | `TestResult` | 测试某个 Provider 的连通性 | P0 |
| `LlmService.reloadProvider(...)` | `void` | 重新加载单个 Provider（配置变更时） | P1 |

### 10.2 数据模型

```kotlin
data class ActiveInfo(
    val provider: String?,
    val model: String?
)

data class ProviderInfo(
    val name: String,
    val displayName: String,
    val region: String,          // "cn" / "global"
    val enabled: Boolean,
    val hasApiKey: Boolean,
    val currentModel: String?,
    val models: List<String>,
    val description: String?
)

data class TestResult(
    val success: Boolean,
    val provider: String,
    val model: String?,
    val responseTime: Long,      // ms
    val response: String?,       // 简短回复
    val error: String?
)
```

### 10.3 JS Bridge 消息协议

ForgeChatPanel 中 JS ↔ Kotlin 的消息协议保持不变：

**JS → Kotlin：**
```json
{ "type": "sendMessage", "content": "帮我优化这段代码", "sessionId": "xxx" }
{ "type": "switchModel", "provider": "deepseek", "model": "deepseek-chat" }
{ "type": "getModels" }
{ "type": "cancelMessage" }
{ "type": "applyCode", "code": "...", "language": "java" }
```

**Kotlin → JS：**
```javascript
window.appendToken("你好，")          // 流式追加
window.onStreamDone()                 // 流式结束
window.onStreamStart()                // 流式开始
window.updateModels({...})            // 更新模型列表
window.onModelSwitched("deepseek", "deepseek-chat")  // 模型已切换
window.onError("错误信息")            // 错误提示
```

---

## 11. 数据存储设计

### 11.1 配置存储（本地，IntelliJ 持久化）

> **v1.0 变化**：所有配置（包括 API Key、模型选择）都存储在本地，不再依赖外部后端的 config.json。

通过 IntelliJ Platform 的 `PersistentStateComponent` 存储到 XML 文件：

```kotlin
// ForgeSettings.kt
@State(name = "ForgeCodeSettings", storages = [Storage("forge-code.xml")])
class ForgeSettings : PersistentStateComponent<ForgeSettings.State> {
    data class State(
        // ===== 模型配置 =====
        /** 当前激活的提供商名称 */
        var activeProvider: String = "",
        /** 当前激活的模型名称 */
        var activeModel: String = "",
        /** 各提供商配置（JSON 序列化存储） */
        var providerConfigs: String = "{}",

        // ===== 通用设置 =====
        /** 连接超时（秒） */
        var connectTimeout: Int = 30,
        /** 读取超时（秒） */
        var readTimeout: Int = 120,

        // ===== 界面设置 =====
        /** UI 主题：auto / dark / light */
        var theme: String = "auto",
        /** 界面语言：zh / en */
        var language: String = "zh",
        /** 默认对话模式：vibe / spec */
        var defaultMode: String = "vibe"
    )

    // 提供 Map 形式的 Provider 配置读写
    fun getProviderConfigMap(): Map<String, ProviderConfig> { ... }
    fun setProviderConfig(name: String, config: ProviderConfig) { ... }
}
```

存储位置：`~/.config/JetBrains/[IDE版本]/options/forge-code.xml`

**存储内容示例（forge-code.xml）：**

```xml
<application>
  <component name="ForgeCodeSettings">
    <option name="activeProvider" value="deepseek" />
    <option name="activeModel" value="deepseek-chat" />
    <option name="providerConfigs" value='{
      "deepseek": {"apiKey":"sk-xxx","enabled":true,"currentModel":"deepseek-chat"},
      "openai":   {"apiKey":"sk-yyy","enabled":true,"currentModel":"gpt-4o"}
    }' />
    <option name="theme" value="auto" />
    <option name="language" value="zh" />
  </component>
</application>
```

> **安全提示**：API Key 存储在本地 IDE 配置目录中，不会上传到任何服务器。
> IntelliJ 的 options 目录默认受操作系统用户权限保护。
> 后续可考虑使用 IntelliJ 的 `PasswordSafe` API 加密存储 API Key。

### 11.2 会话存储

会话数据存储在本地文件系统：

```
~/.config/JetBrains/[IDE版本]/forge-code/
├── sessions/
│   ├── session-uuid-1.json     # 每个会话一个 JSON 文件
│   ├── session-uuid-2.json
│   └── ...
└── sessions-index.json          # 会话索引（名称、时间等）
```

会话 JSON 格式：
```json
{
  "id": "uuid-xxx",
  "title": "优化排序算法",
  "createdAt": "2026-04-14T10:00:00Z",
  "updatedAt": "2026-04-14T10:30:00Z",
  "messages": [
    {"role": "user", "content": "帮我优化这段代码..."},
    {"role": "assistant", "content": "好的，我来帮你..."}
  ]
}
```

---

## 12. 开发路线图

> **对标产品**：Claude Code、CodeMaker、GitHub Copilot、通义灵码
>
> **核心差异化**：聚合国内外 16+ 大模型 + 全功能 Agent + 开源可自部署

---

### Phase 1 · MVP（✅ 已完成）

**目标**：IDEA 插件能对话、能切换模型，零依赖启动

- [x] 搭建 `forge-code` 工程骨架（Kotlin + Gradle + Java 混编）
- [x] 实现 IDEA ToolWindow + JCEF 聊天面板
- [x] 实现基础聊天 UI（HTML/CSS/JS + Markdown 渲染）
- [x] 实现 JS Bridge（消息发送/流式接收）
- [x] 实现底部状态栏模型切换 Widget
- [x] 实现 Settings 设置页（Provider 配置 + API Key）
- [x] 实现右键菜单（解释/优化/审查/生成测试）
- [x] 移植 claude-api-proxy Provider 层（16+ 大模型）
- [x] 改造 BackendService → LlmService（直调 Provider，无需外部后端）
- [x] 改造 ForgeSettings（Provider 配置本地持久化）
- [x] 实现模型自动切换 + 错误自动重试 + 手动重试按钮
- [x] 流式 Markdown 渲染（marked.js + 节流优化）
- [x] thinking 动画、模型 Logo、错误友好提示

**交付物**：可在 IDEA 中安装的 `.zip` 插件包，无需外部后端，直接调用大模型 API

---

### Phase 2 · 编辑器深度集成（🔄 当前阶段，目标：1个月内）

**目标**：编辑器联动、代码 Apply、多会话、行内补全

#### 2.1 代码 Apply — Diff 视图写入（P0，对标核心功能）

> 对标：Claude Code 的 Apply 按钮、CodeMaker 的代码写入

- [ ] AI 回复的代码块增加 **[Apply]** 按钮
- [ ] 点击 Apply → 调用 IntelliJ Diff API 弹出 Diff 视图
- [ ] 用户在 Diff 视图中 Accept / Reject 每处修改
- [ ] Accept 后写入目标文件（支持新建文件）
- [ ] 支持多文件 Apply（AI 同时修改多个文件）

```
流程：
AI 返回代码块 → [Apply] 按钮
    → JS Bridge 发送 applyCode 消息
    → Kotlin 调用 WriteCommandAction + VirtualFile API
    → 弹出 DiffManager.showDiff() 视图
    → 用户确认 → 写入文件
```

#### 2.2 @文件引用 — 上下文注入（P0，对标核心功能）

> 对标：Claude Code 的 @file、CodeMaker 的文件引用

- [ ] 输入框支持 `@` 触发文件/符号搜索弹窗
- [ ] 搜索结果展示当前项目的文件列表（模糊搜索）
- [ ] 选中文件后将文件内容附加到对话上下文
- [ ] 支持 `@文件名`、`@文件夹`（整个目录）、`@符号名`（函数/类）
- [ ] UI 显示已引用的文件标签（可删除）
- [ ] 限制总 token 数（超出时截断并提示）

```
UI 交互：
用户输入 @ → 弹出文件搜索下拉
    → 选中 UserService.java
    → 输入框显示 [@UserService.java] 标签
    → 发送时将文件内容注入 system 或 user 消息
```

#### 2.3 多会话历史管理（P0）

> 对标：Claude Code 的会话列表、CodeMaker 的历史记录

- [ ] 左上角 **[历史]** 按钮打开会话列表侧边栏
- [ ] 会话列表显示：标题（首条消息摘要）、时间、模型
- [ ] 支持新建、切换、重命名、删除会话
- [ ] 会话数据持久化到本地 JSON 文件
- [ ] 会话搜索（关键词搜索历史记录）
- [ ] 会话自动命名（取首条消息前 20 字）

```
存储位置：
~/.config/JetBrains/[IDE版本]/forge-code/sessions/
    session-uuid-1.json
    session-uuid-2.json
    sessions-index.json   ← 索引（标题、时间、模型、messageCount）
```

#### 2.4 行内代码补全（P0，对标核心功能）

> 对标：GitHub Copilot 的行内补全、通义灵码的补全

- [ ] 编辑器输入时触发行内补全（Ghost Text，灰色文字）
- [ ] 支持 `Tab` 接受补全、`Esc` 拒绝
- [ ] 补全策略：当前行 + 前后 N 行上下文
- [ ] 支持防抖（300ms 后无输入才触发）
- [ ] 支持按语言选择补全模型（可配置）
- [ ] 补全结果缓存（相同前缀复用）

```kotlin
// 实现方式：实现 IntelliJ EditorFactoryListener
// 使用 InlineCompletionProvider API（IntelliJ 2024.1+）
// 或 InlayHintsProvider 实现兼容旧版本
class ForgeInlineCompletionProvider : InlineCompletionProvider {
    override fun getProposals(request: InlineCompletionRequest): Flow<InlineCompletionElement>
}
```

#### 2.5 API Key 安全存储（P0）

> 当前明文存储 XML，需迁移到系统钥匙串

- [ ] 将 API Key 迁移到 IntelliJ `PasswordSafe` API
- [ ] 设置页展示时 API Key 显示为 `sk-****1234`（末 4 位可见）
- [ ] 导出配置时 API Key 不导出（安全起见）

```kotlin
// 使用 IntelliJ PasswordSafe
PasswordSafe.instance.setPassword(
    CredentialAttributes("ForgeCode.deepseek"),
    "sk-xxxx"
)
```

#### 2.6 自定义 OpenAI 兼容 API（P1）

> 对标：CodeMaker 的自定义端点、支持私有化部署模型

- [ ] 设置页增加 **[+ 添加自定义提供商]** 按钮
- [ ] 输入：名称、Base URL、API Key、模型列表（逗号分隔）
- [ ] 自动测试连通性
- [ ] 支持 Ollama 本地模型（无需 API Key）
- [ ] 支持 LM Studio、vLLM 等本地服务

**交付物**：功能完整版本，可发布到 JetBrains Marketplace

---

### Phase 3 · Agent 能力（目标：2个月内）

**目标**：具备真正的 Agent 能力，对标 Claude Code 的 Agentic 模式

#### 3.1 Spec 模式（Plan → 确认 → 执行）

> 对标：Claude Code 的 Plan Mode、Cursor 的 Composer

- [ ] 用户描述需求 → AI 生成**实现计划**（分步骤列表）
- [ ] 用户可编辑/确认计划
- [ ] AI 按计划逐步执行（每步前显示进度）
- [ ] 支持中途暂停、回退到某步

#### 3.2 Agent 工具集

> 对标：Claude Code 的 Tools（read_file, write_file, bash）

- [ ] **read_file** — 读取指定文件内容
- [ ] **write_file** — 写入/修改文件
- [ ] **list_files** — 列举目录文件
- [ ] **run_terminal** — 在 IDE 内置终端执行命令
- [ ] **search_code** — 全局代码搜索（ripgrep）
- [ ] **apply_diff** — 直接 Apply 差异补丁

```kotlin
// 工具调用协议（OpenAI Function Calling）
{
  "tools": [
    {"name": "read_file", "parameters": {"path": "src/UserService.java"}},
    {"name": "write_file", "parameters": {"path": "...", "content": "..."}},
    {"name": "run_terminal", "parameters": {"command": "mvn test"}}
  ]
}
```

#### 3.3 代码库索引

> 对标：Claude Code 的项目感知、Cursor 的 Codebase

- [ ] 项目打开时自动扫描代码库（后台线程）
- [ ] 建立文件/符号索引（利用 IntelliJ PSI API）
- [ ] 支持语义搜索（可选，接入 embedding 模型）
- [ ] `@codebase` 引用整个项目（自动选取相关文件）

#### 3.4 Rules 规则系统

> 对标：Claude Code 的 CLAUDE.md、Cursor 的 .cursorrules

- [ ] 支持项目根目录 `.forgecode` 文件（持久指令）
- [ ] 支持全局 Rules 配置（适用于所有项目）
- [ ] 内容自动注入到每次对话的 system 消息
- [ ] UI 支持在设置页编辑 Rules

#### 3.5 Review Tab — 代码审查视图

- [ ] 独立的 Review 标签页（区别于 Chat）
- [ ] 对当前文件/选中代码做全面审查
- [ ] 问题列表展示（可点击跳转到代码位置）
- [ ] 支持 Git Diff 审查（提交前审查）

#### 3.6 MCP 协议支持（Model Context Protocol）

> 对标：Claude Code 的 MCP、Cursor 的 MCP

- [ ] 支持配置 MCP Server（外部工具扩展）
- [ ] 支持常见 MCP 工具：文件系统、数据库、浏览器等
- [ ] 工具调用结果自动注入对话上下文

---

### Phase 4 · 生态扩展（目标：3个月内）

#### 4.1 VSCode 插件

> 复用 WebUI + Provider 层，VSCode 插件接口层单独实现

- [ ] VSCode Extension 骨架（TypeScript）
- [ ] 聊天面板（WebView 复用同一套 HTML/CSS/JS）
- [ ] Provider 层通过 HTTP 调用（Java 进程作为本地服务）
- [ ] 状态栏模型切换

#### 4.2 团队/企业功能

- [ ] 团队共享 API Key（加密中转，不暴露原始 Key）
- [ ] 使用统计（每日 token 用量、模型分布）
- [ ] 企业私有化部署（插件指向内部 LLM 网关）

#### 4.3 插件市场发布

- [ ] 完善 plugin.xml 描述、截图、更新日志
- [ ] 发布到 JetBrains Marketplace
- [ ] 支持插件自动更新

---

### 各阶段对标功能完整度评估

| 功能 | Claude Code | CodeMaker | **Forge Code P1** | **Forge Code P2** | **Forge Code P3** |
|------|:-----------:|:---------:|:-----------------:|:-----------------:|:-----------------:|
| 多模型支持 | ❌ | ✅ | ✅ | ✅ | ✅ |
| 国内模型 | ❌ | ✅ | ✅ | ✅ | ✅ |
| 聊天对话 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 代码 Apply | ✅ | ✅ | ❌ | ✅ | ✅ |
| @文件引用 | ✅ | ✅ | ❌ | ✅ | ✅ |
| 多会话历史 | ✅ | ✅ | ❌ | ✅ | ✅ |
| 行内补全 | ✅ | ✅ | ❌ | ✅ | ✅ |
| 自定义 API | ❌ | ✅ | ❌ | ✅ | ✅ |
| Agent 工具 | ✅ | ❌ | ❌ | ❌ | ✅ |
| Spec 模式 | ✅ | ❌ | ❌ | ❌ | ✅ |
| 代码库索引 | ✅ | ❌ | ❌ | ❌ | ✅ |
| Rules 系统 | ✅ | ❌ | ❌ | ❌ | ✅ |
| MCP 支持 | ✅ | ❌ | ❌ | ❌ | ✅ |
| VSCode 插件 | N/A | ✅ | ❌ | ❌ | ✅ |
| 开源免费 | ❌ | ❌ | ✅ | ✅ | ✅ |

---

## 附录

### A. 技术参考资料

| 资源 | 链接 |
|------|------|
| IntelliJ Platform SDK 文档 | https://plugins.jetbrains.com/docs/intellij/welcome.html |
| JCEF in IntelliJ 文档 | https://plugins.jetbrains.com/docs/intellij/jcef.html |
| IntelliJ PersistentStateComponent | https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html |
| IntelliJ PasswordSafe | https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html |
| OkHttp SSE | https://square.github.io/okhttp/recipes/#handle-server-sent-events-kt-java |
| VSCode Extension API | https://code.visualstudio.com/api |
| claude-api-proxy (代码参考源) | 本地工程 `D:/workspace/claude-api-proxy/` |

### B. 移植对照表（claude-api-proxy → forge-code）

| 原包名 (claude-api-proxy) | 新包名 (forge-code) |
|---------------------------|---------------------|
| `com.claude.proxy.dto.openai` | `com.forgecode.plugin.llm.dto` |
| `com.claude.proxy.provider` | `com.forgecode.plugin.llm.provider` |
| `com.claude.proxy.provider.impl` | `com.forgecode.plugin.llm.provider.impl` |
| `com.claude.proxy.config.ConfigStore` | _(废弃，用 Kotlin ForgeSettings 替代)_ |
| `com.claude.proxy.controller` | _(废弃，插件无需 HTTP 接口)_ |
| `com.claude.proxy.converter` | _(废弃，代理格式转换不需要)_ |

### C. 去掉的依赖

移植 Java 代码时需要去掉的依赖：

| 原依赖 | 替代方案 |
|--------|----------|
| Spring `@Component` / `@PostConstruct` | 手动初始化，由 Kotlin `LlmService` 调用 |
| Spring `@RestController` / `@RequestMapping` | 不需要，插件无 HTTP 接口 |
| Lombok `@Data` / `@Slf4j` / `@Builder` | 手写 getter/setter，用 `java.util.logging` 或 IntelliJ Logger |
| Jackson `@JsonProperty` | 保留，Gson 也可直接使用字段名 |
| Lombok `@RequiredArgsConstructor` | 手写构造函数 |
